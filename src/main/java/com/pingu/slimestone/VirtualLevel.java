package com.pingu.slimestone;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.material.PushReaction;

import java.util.*;


public class VirtualLevel {

    public static final int UPDATE_NEIGHBORS = 1;
    public static final int UPDATE_CLIENTS = 2;
    public static final int UPDATE_INVISIBLE = 4;
    public static final int UPDATE_KNOWN_SHAPE = 16;
    public static final int UPDATE_SUPPRESS_DROPS = 32;
    public static final int UPDATE_MOVED_BY_PISTON = 64;
    public static final int UPDATE_ALL = UPDATE_NEIGHBORS | UPDATE_CLIENTS;

    private static final Direction[] UPDATE_ORDER = new Direction[]{Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};

    private final ServerPlayer player;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();

    // Package-private so the Resolver can verify moving pistons
    final Map<BlockPos, SimPistonMovingEntity> blockEntities = new LinkedHashMap<>();

    private final Queue<BlockEvent> blockEvents = new ArrayDeque<>();
    private final Queue<NeighborUpdate> neighborUpdates = new ArrayDeque<>();

    private int currentTick = 0;
    private String currentPhase = "INIT";

    /**
     * Pending scheduled block ticks ("tile ticks"), e.g. an Observer's
     * falling-edge pulse. Ordered like vanilla's {@code ScheduledTick.DRAIN_ORDER}
     * (earlier {@code triggerTick} first, ties broken by insertion order).
     */
    private final PriorityQueue<SimScheduledTick> scheduledBlockTicks = new PriorityQueue<>();
    private long subTickOrderCounter = 0;

    public VirtualLevel(ServerPlayer player) {
        this.player = player;
    }

    public void log(String msg) {
        String phaseColor = switch (currentPhase) {
            case "INIT" -> "§b";               // Aqua
            case "TILE TICK" -> "§3";          // Dark Aqua
            case "BLOCK ENTITIES" -> "§e";     // Yellow
            case "BLOCK EVENTS" -> "§6";       // Gold
            case "NEIGHBOR UPDATES" -> "§d";   // Light Purple
            default -> "§7";                   // Gray
        };
        player.sendSystemMessage(Component.literal(String.format("%s[GT %d | %s] §f%s", phaseColor, currentTick, currentPhase, msg)));
    }

    public BlockState getBlockState(BlockPos pos) {
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    public void setBlockRaw(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
    }

    public void setBlock(BlockPos pos, BlockState state) {
        setBlock(pos, state, UPDATE_ALL); // Default to flag 3
    }

    public void setBlock(BlockPos pos, BlockState state, int flags) {
        BlockState oldState = getBlockState(pos);
        setBlockRaw(pos, state);
        log("Set block at " + pos.toShortString() + " to " + state.getBlock().getName().getString());

        // Emulate vanilla calling onPlace immediately after setting the chunk data
        handleOnPlace(pos, state, oldState);

        // Fetch the state AGAIN to see if onPlace modified it recursively
        BlockState currentState = getBlockState(pos);

        // THE VANILLA CHECK: If onPlace changed the state, suppress all automated updates
        if (currentState == state) {
            if ((flags & UPDATE_NEIGHBORS) != 0) {
                updateNeighborsAt(pos);
            }

            if ((flags & UPDATE_KNOWN_SHAPE) == 0) {
                fireShapeUpdates(pos); // Tell neighbors our shape changed
                evaluateOwnShape(pos, currentState); // Tell ourselves to evaluate surroundings
            }
        } else {
            log("§8[Vanilla Mechanics] Updates suppressed at " + pos.toShortString() + " because onPlace modified the state.");
        }
    }

    private void handleOnPlace(BlockPos pos, BlockState state, BlockState oldState) {
        if (state.is(oldState.getBlock())) return;

        // --- Observer Placement / Landing Logic ---
        if (state.is(Blocks.OBSERVER)) {
            boolean isPowered = state.getValue(BlockStateProperties.POWERED);

            // Vanilla ObserverBlock.onPlace: Reset spurious powered state if no tick is pending.
            if (isPowered && !hasScheduledTick(pos, Blocks.OBSERVER)) {
                BlockState unpowered = state.setValue(BlockStateProperties.POWERED, false);

                // Vanilla uses flag 18: UPDATE_KNOWN_SHAPE (16) | UPDATE_CLIENTS (2)
                // This intentionally bypasses UPDATE_NEIGHBORS (1) for this nested call
                setBlock(pos, unpowered, UPDATE_KNOWN_SHAPE | UPDATE_CLIENTS);

                log("§3[Observer] onPlace reset spurious POWERED state at " + pos.toShortString());
                updateNeighborsFromObserver(pos, unpowered);
            }
        }
    }

    private void evaluateOwnShape(BlockPos pos, BlockState state) {
        // Emulates a placed block running its own shape checks against neighbors upon landing
        if (state.is(Blocks.OBSERVER)) {
            if (!state.getValue(BlockStateProperties.POWERED) && !hasScheduledTick(pos, Blocks.OBSERVER)) {
                log("§3[Schedule] Observer at " + pos.toShortString() + " → fires next tick");
                scheduleTick(pos, Blocks.OBSERVER, 2);
            }
        }
    }
    /**
     * Returns true if there is already a pending tick for {@code (pos, type)}.
     * Mirrors {@code LevelTickAccess.hasScheduledTick(pos, type)}.
     */
    public boolean hasScheduledTick(BlockPos pos, Block type) {
        for (SimScheduledTick t : scheduledBlockTicks) {
            if (t.type() == type && t.pos().equals(pos)) return true;
        }
        return false;
    }

    /**
     * Queues a block tick to fire at {@code currentTick + delay}.
     * Duplicate ticks (same pos+type) are silently ignored, matching vanilla's
     * {@code startSignal} guard ({@code !ticks.getBlockTicks().hasScheduledTick(pos, this)}).
     */
    public void scheduleTick(BlockPos pos, Block type, int delay) {
        if (hasScheduledTick(pos, type)) return;
        long fireTick = currentTick + delay;
        scheduledBlockTicks.add(new SimScheduledTick(pos.immutable(), type, fireTick, subTickOrderCounter++));
        // Removed the universal log here.
        // This stops the simulator from logging self-reschedules (like turn-off ticks),
        // perfectly mirroring how vanilla's level.scheduleTick() avoids the startSignal Mixin.
    }

    public void runTickLoop(int maxTicks) {
        for (currentTick = 0; currentTick < maxTicks; currentTick++) {
            boolean active = false;

            // ── PHASE 1: TILE TICK (scheduled block ticks) ───────────────────
            // Independent phase: drains every due tick (and its own neighbor
            // updates) before BLOCK EVENTS even looks at the queue.
            currentPhase = "TILE TICK";
            while (!scheduledBlockTicks.isEmpty() && scheduledBlockTicks.peek().triggerTick() <= currentTick) {
                active = true;
                SimScheduledTick tick = scheduledBlockTicks.poll();
                processScheduledTick(tick);
                flushNeighborUpdates();
            }

            // ── PHASE 2: BLOCK EVENTS ─────────────────────────────────────────
            currentPhase = "BLOCK EVENTS";
            while (!blockEvents.isEmpty()) {
                active = true;
                BlockEvent event = blockEvents.peek();
                triggerPistonEvent(event.pos(), event.state(), event.type(), event.dir());
                flushNeighborUpdates();
                blockEvents.poll();
            }

            // ── PHASE 3: BLOCK ENTITIES ────────────────────────────────────────
            currentPhase = "BLOCK ENTITIES";
            List<SimPistonMovingEntity> tickingBEs = new ArrayList<>(blockEntities.values());
            for (SimPistonMovingEntity be : tickingBEs) {
                active = true;
                be.tick(this);
                flushNeighborUpdates();
            }



            if (!active && blockEvents.isEmpty() && neighborUpdates.isEmpty() && scheduledBlockTicks.isEmpty()) {
                log("§8Simulation settled. Halting early.");
                break;
            }
        }
    }

    /**
     * Drains a single due tile tick. Mirrors {@code LevelTicks}: if the block
     * at this position is no longer the type the tick was scheduled for, the
     * tick is silently dropped instead of firing.
     */
    private void processScheduledTick(SimScheduledTick tick) {
        BlockState state = getBlockState(tick.pos());

        if (!state.is(tick.type())) {
            log("§8[Tick] Cancelled stale " + tick.type().getName().getString()
                    + " tick at " + tick.pos().toShortString() + " (block changed)");
            return;
        }

        if (tick.type() == Blocks.OBSERVER) {
            processObserverTick(tick.pos(), state);
        }
        // Other scheduled-tick block types (e.g. pistons, repeaters) would be
        // dispatched here as the simulation grows to cover them.
    }

    private void processObserverTick(BlockPos pos, BlockState state) {
        boolean wasPowered = state.getValue(BlockStateProperties.POWERED);

        if (wasPowered) {
            BlockState unpowered = state.setValue(BlockStateProperties.POWERED, false);
            setBlockRaw(pos, unpowered);
            log("§3[Observer] " + pos.toShortString() + " → POWERED=false (pulse end)");

            // 1. Emit Shape Updates to immediate neighbors (corresponds to vanilla setBlock flag 2)
            fireShapeUpdates(pos);
            // 2. Emit Redstone Updates to the back block and its neighbors
            updateNeighborsFromObserver(pos, unpowered);
        } else {
            BlockState powered = state.setValue(BlockStateProperties.POWERED, true);
            setBlockRaw(pos, powered);
            log("§3[Observer] " + pos.toShortString() + " → POWERED=true (pulse start)");

            fireShapeUpdates(pos);                   // neighbor cascade first, like vanilla setBlock
            scheduleTick(pos, Blocks.OBSERVER, 2);   // self-reschedule second, like vanilla's scheduleTick after setBlock

            updateNeighborsFromObserver(pos, powered);
        }
    }

    private void updateNeighborsFromObserver(BlockPos obsPos, BlockState obsState) {
        Direction facing = obsState.getValue(BlockStateProperties.FACING);
        BlockPos outputPos = obsPos.relative(facing.getOpposite());

        // 1. Emulate vanilla neighborChanged for the output block directly.
        neighborUpdates.add(new NeighborUpdate(outputPos, obsPos));

        // 2. Emulate updateNeighborsAtExceptFromFacing.
        for (Direction dir : UPDATE_ORDER) {
            // Skip the direction pointing back at the observer
            if (dir == facing) continue;
            neighborUpdates.add(new NeighborUpdate(outputPos.relative(dir), outputPos));
        }
    }

    private void flushNeighborUpdates() {
        String previousPhase = currentPhase;
        currentPhase = "NEIGHBOR UPDATES";
        while (!neighborUpdates.isEmpty()) {
            NeighborUpdate update = neighborUpdates.poll();
            BlockState state = getBlockState(update.pos());
            // Pass the fromPos to the handler
            handleNeighborUpdate(update.pos(), state, update.fromPos());
        }
        currentPhase = previousPhase;
    }

    public void updateNeighborsAt(BlockPos pos) {
        String previousPhase = currentPhase;
        currentPhase = "NEIGHBOR UPDATES";

        for (Direction dir : UPDATE_ORDER) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState state = getBlockState(neighborPos);
            // Pass 'pos' as the fromPos
            handleNeighborUpdate(neighborPos, state, pos);
        }

        currentPhase = previousPhase;
    }


    private void handleNeighborUpdate(BlockPos pos, BlockState state, BlockPos fromPos) {

        // --- NOTE BLOCK ---
        if (state.is(Blocks.NOTE_BLOCK)) {
            handleNoteBlockNeighborChanged(pos, state);
        }

        // --- EXISTING PISTON LOGIC ---
        else if (state.getBlock() instanceof PistonBaseBlock) {
            checkIfExtend(pos, state);
        } else if (state.getBlock() instanceof PistonHeadBlock) {
            Direction facing = state.getValue(PistonHeadBlock.FACING);
            BlockPos basePos = pos.relative(facing.getOpposite());
            BlockState baseState = getBlockState(basePos);

            // 1. Emulate vanilla PistonHeadBlock.canSurvive()
            PistonType headType = state.getValue(PistonHeadBlock.TYPE);
            Block expectedBase = headType == PistonType.DEFAULT ? Blocks.PISTON : Blocks.STICKY_PISTON;

            boolean isFittingBase = baseState.is(expectedBase)
                    && baseState.getValue(PistonBaseBlock.EXTENDED)
                    && baseState.getValue(PistonBaseBlock.FACING) == facing;

            boolean isMovingBase = baseState.is(Blocks.MOVING_PISTON)
                    && baseState.getValue(BlockStateProperties.FACING) == facing;

            if (!isFittingBase && !isMovingBase) {
                log("§c[Survival] PISTON_HEAD at " + pos.toShortString() + " lost its valid base, destroying it");
                setBlock(pos, Blocks.AIR.defaultBlockState());
                log("§d[Survival] Firing neighbor updates for destroyed PISTON_HEAD at: " + pos.toShortString());
                updateNeighborsAt(pos);
                return; // Stop processing since the head is now Air
            }

            // 2. If it survives, pass the update to the base (emulating vanilla neighborChanged)
            if (baseState.getBlock() instanceof PistonBaseBlock) {
                checkIfExtend(basePos, baseState);
            }
        }
    }
    public void fireShapeUpdates(BlockPos pos) {
        String previousPhase = currentPhase;
        currentPhase = "SHAPE UPDATES";

        for (Direction dir : UPDATE_ORDER) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState state = getBlockState(neighborPos);

            if (state.is(Blocks.OBSERVER)) {
                // Emulate the Mixin's updateShape logging exactly
                log("§6[ShapeUpdate] Detected update from " + dir.getOpposite().getName() + " side at " + neighborPos.toShortString());

                Direction facing = state.getValue(BlockStateProperties.FACING);
                BlockPos lookingAt = neighborPos.relative(facing);

                if (lookingAt.equals(pos)) {
                    // This block matches vanilla's startSignal behavior
                    if (!state.getValue(BlockStateProperties.POWERED) && !hasScheduledTick(neighborPos, Blocks.OBSERVER)) {
                        log("§3[Schedule] Observer at " + neighborPos.toShortString() + " → fires next tick");
                        scheduleTick(neighborPos, Blocks.OBSERVER, 2);
                    }
                }
            } else if (state.is(Blocks.NOTE_BLOCK)) {
                // NoteBlock.updateShape only acts on Y-axis neighbours (INSTRUMENT change).
                // In vanilla, directionToNeighbour is the direction FROM the note block TO
                // the changed block, which here is dir.getOpposite(). Axis.Y covers both
                // UP and DOWN, so dir.getAxis() == Y is equivalent.
                if (dir.getAxis() == Direction.Axis.Y) {
                    BlockState updated = simSetNoteBlockInstrument(neighborPos, state);
                    if (updated != state) {
                        setBlockRaw(neighborPos, updated);
                        log("§a[NoteBlock] Instrument updated at " + neighborPos.toShortString()
                                + " → " + updated.getValue(BlockStateProperties.NOTEBLOCK_INSTRUMENT).name());
                    }
                }
                // Non-Y directions fall through super.updateShape, which is a no-op for NoteBlock.
            }
        }

        currentPhase = previousPhase;
    }

    /**
     * Mirrors {@code PistonBaseBlock.getNeighborSignal}: checks every neighbour
     * of the piston (except the one it's facing) for a redstone signal, the
     * position directly above (covered explicitly so an UP-facing piston still
     * sees it, since the main loop skips its own facing direction), and the
     * quasi-connectivity (QC) positions around the block above the piston.
     */
    public boolean hasRedstoneBlockPower(BlockPos pos, Direction pistonFacing) {
        // ── Loop 1: Direct neighbours (all sides except the piston's push face)
        for (Direction dir : Direction.values()) {
            if (dir == pistonFacing) continue;
            if (simHasSignal(pos.relative(dir), dir)) return true;
        }

        // ── Loop 2: QC — neighbours of the block above (all sides except below)
        BlockPos above = pos.above();
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            if (simHasSignal(above.relative(dir), dir)) return true;
        }

        return false;
    }

    /**
     * Mirrors {@code LevelReader.hasSignal}: true if the block at
     * {@code blockPos} either directly emits a signal toward {@code queryDir},
     * or — for a solid conductor — re-emits a direct ("strong") signal it's
     * receiving from one of its own neighbours.
     */
    private boolean simHasSignal(BlockPos blockPos, Direction queryDir) {
        BlockState state = getBlockState(blockPos);

        if (state.is(Blocks.REDSTONE_BLOCK)) return true;

        // Vanilla logic: An observer emits signal 15 if powered AND its FACING matches the queried direction.
        if (state.is(Blocks.OBSERVER)
                && state.getValue(BlockStateProperties.POWERED)
                && state.getValue(BlockStateProperties.FACING) == queryDir) {
            return true;
        }

        // Solid conductors re-emit direct (strong) signals from their neighbours.
        if (isSimRedstoneConductor(state)) {
            return simGetDirectSignalTo(blockPos) > 0;
        }

        return false;
    }
    private boolean isSimRedstoneConductor(BlockState state) {
        if (state.isAir()) return false;
        if (state.is(Blocks.REDSTONE_BLOCK)) return false;
        if (state.is(Blocks.OBSERVER)) return false;
        if (state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON)) return false;
        if (state.is(Blocks.PISTON_HEAD) || state.is(Blocks.MOVING_PISTON)) return false;
        if (state.is(Blocks.HONEY_BLOCK)) return false; // Slime blocks are solid blocks unlike honey
        return true;
    }

    private int simGetDirectSignalTo(BlockPos blockPos) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = blockPos.relative(dir);
            BlockState neighborState = getBlockState(neighborPos);



            // Ensure strong power is checked from adjacent observers
            if (neighborState.is(Blocks.OBSERVER)
                    && neighborState.getValue(BlockStateProperties.POWERED)
                    && neighborState.getValue(BlockStateProperties.FACING) == dir) {
                return 15;
            }
        }
        return 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NOTE BLOCK
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors {@code Level.hasNeighborSignal}: returns true if any of the six
     * immediate neighbours of {@code pos} emits a redstone signal toward it.
     *
     * Unlike {@link #hasRedstoneBlockPower} this checks all six faces with no
     * facing exclusion and no quasi-connectivity (QC), matching the call
     * {@code level.hasNeighborSignal(pos)} inside NoteBlock.neighborChanged.
     */
    public boolean simHasNeighborSignal(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (simHasSignal(pos.relative(dir), dir)) return true;
        }
        return false;
    }

    /**
     * Mirrors {@code NoteBlock.neighborChanged}: toggles POWERED when the
     * incoming signal level changes. Sound / blockEvent logic is intentionally
     * skipped — it queues a blockEvent that only plays audio and has zero
     * redstone consequence.
     *
     * Vanilla call order on a rising edge:
     *   1. playNote → level.blockEvent (audio, skipped here)
     *   2. level.setBlock(pos, powered=true, flag 3)
     *
     * On a falling edge only step 2 occurs (no sound on power loss).
     * We replicate both orderings faithfully minus the audio.
     */
    private void handleNoteBlockNeighborChanged(BlockPos pos, BlockState state) {
        boolean signal   = simHasNeighborSignal(pos);
        boolean wasPowered = state.getValue(BlockStateProperties.POWERED);
        if (signal == wasPowered) return;

        log("§a[NoteBlock] " + pos.toShortString() + " → POWERED=" + signal);
        // flag 3 = UPDATE_ALL (UPDATE_NEIGHBORS | UPDATE_CLIENTS), matching vanilla
        setBlock(pos, state.setValue(BlockStateProperties.POWERED, signal), UPDATE_ALL);
    }

    /**
     * Mirrors the private {@code NoteBlock.setInstrument}: reads the blocks
     * immediately above and below {@code pos} and returns a new state carrying
     * the correct INSTRUMENT. Called only when a Y-axis neighbour changes shape.
     *
     * Rule (from vanilla):
     *   • If the block ABOVE has an instrument that works above a note block
     *     (e.g. a skull), use that instrument.
     *   • Otherwise fall back to the block BELOW's instrument, but if the
     *     block below itself worksAboveNoteBlock, use HARP as default instead.
     */
    private BlockState simSetNoteBlockInstrument(BlockPos pos, BlockState state) {
        NoteBlockInstrument above = getBlockState(pos.above()).instrument();
        if (above.worksAboveNoteBlock()) {
            return state.setValue(BlockStateProperties.NOTEBLOCK_INSTRUMENT, above);
        }
        NoteBlockInstrument below  = getBlockState(pos.below()).instrument();
        NoteBlockInstrument result = below.worksAboveNoteBlock() ? NoteBlockInstrument.HARP : below;
        return state.setValue(BlockStateProperties.NOTEBLOCK_INSTRUMENT, result);
    }

    public void checkIfExtend(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(PistonBaseBlock.FACING);
        boolean powered = hasRedstoneBlockPower(pos, facing);
        boolean extended = state.getValue(PistonBaseBlock.EXTENDED);

        if (powered && !extended) {
            SimPistonResolver resolver = new SimPistonResolver(this, pos, facing, true);
            if (resolver.resolve()) {
                log("Piston powered and path valid at " + pos.toShortString() + ". Queuing Extend.");
                BlockEvent event = new BlockEvent(pos, state, 0, facing);
                if (!blockEvents.contains(event)) blockEvents.add(event);
            }
        } else if (!powered && extended) {
            log("Piston unpowered at " + pos.toShortString() + ". Queuing Contract Event.");
            BlockEvent event = new BlockEvent(pos, state, 1, facing);
            if (!blockEvents.contains(event)) blockEvents.add(event);
        }
    }

    private void triggerPistonEvent(BlockPos pos, BlockState queuedState, int type, Direction queuedFacing) {
        BlockState currentState = getBlockState(pos);

        if (!currentState.is(queuedState.getBlock())) {
            log("§eEvent cancelled: Block at " + pos.toShortString() + " is no longer the correct piston variant.");
            return;
        }

        Direction facing = currentState.getValue(PistonBaseBlock.FACING);
        boolean isPowered = hasRedstoneBlockPower(pos, facing);
        if (type == 0) {
            if (!isPowered) {
                log("§eExtend cancelled: piston lost power before processing at " + pos.toShortString());
                return;
            }

            SimPistonResolver resolver = new SimPistonResolver(this, pos, facing, true);
            if (!resolver.resolve()) {
                log("§cExtend failed: " + resolver.getFailureReason() + " (Pos: " +
                        (resolver.getFailurePos() != null ? resolver.getFailurePos().toShortString() : "N/A") + ")");
                return;
            }

            moveBlocks(pos, currentState, facing, true, resolver);
            boolean extendIsSticky = currentState.getBlock() == Blocks.STICKY_PISTON;
            setBlockRaw(pos, currentState.setValue(PistonBaseBlock.EXTENDED, true));
            fireShapeUpdates(pos);

            log("Set block at " + pos.toShortString() + " to "
                    + (extendIsSticky ? "Sticky Piston Headless Base" : "Piston Headless Base"));
            //log("Firing neighbor updates for piston base at: " + pos.toShortString());
            updateNeighborsAt(pos);
        } else if (type == 1) {
            if (isPowered) {
                log("§eRetract cancelled: piston re-gained power at " + pos.toShortString());
                setBlockRaw(pos, currentState.setValue(PistonBaseBlock.EXTENDED, true));
                return;
            }

            boolean isSticky = currentState.getBlock() == Blocks.STICKY_PISTON;
            BlockPos headPos = pos.relative(facing);

            // 1. Unconditionally final-tick ANY moving block at the head position (Vanilla Step 1)
            SimPistonMovingEntity headBE = blockEntities.get(headPos);
            if (headBE != null) {
                log("Instant finalTick on moving head at " + headPos.toShortString());
                headBE.finalTick(this);
            }

            // 2. Base transitions to MOVING_PISTON
            PistonType pType = isSticky ? PistonType.STICKY : PistonType.DEFAULT;
            BlockState movingBaseState = Blocks.MOVING_PISTON.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, facing)
                    .setValue(PistonHeadBlock.TYPE, pType);
            BlockState unextendedBase = currentState.setValue(PistonBaseBlock.EXTENDED, false);

            setBlockRaw(pos, movingBaseState);
            blockEntities.put(pos, new SimPistonMovingEntity(pos, unextendedBase, facing, false, true));
            fireShapeUpdates(pos); // Add this line
            log("Created MovingPiston BE at " + pos.toShortString() + " for Retracting "
                    + (isSticky ? "Sticky " : "") + "Base");
            //log("Firing neighbor updates for retracting base at: " + pos.toShortString());
            updateNeighborsAt(pos);
            if (isSticky) {
                BlockPos twoAheadPos = pos.relative(facing, 2);
                SimPistonMovingEntity twoAheadBE = blockEntities.get(twoAheadPos);
                boolean instantFinished = false;

                if (twoAheadBE != null && twoAheadBE.extending && twoAheadBE.direction == facing) {
                    log("Instant finalTick on mid-push block at " + twoAheadPos.toShortString());
                    twoAheadBE.finalTick(this);
                    instantFinished = true;
                }

                if (!instantFinished) {
                    BlockState twoAheadState = getBlockState(twoAheadPos);
                    boolean shouldPull = !twoAheadState.isAir()
                            && isPushableForPull(twoAheadState, twoAheadPos, facing.getOpposite(), facing)
                            && (twoAheadState.getPistonPushReaction() == PushReaction.NORMAL
                            || twoAheadState.is(Blocks.PISTON)
                            || twoAheadState.is(Blocks.STICKY_PISTON));

                    if (!shouldPull) {
                        // Vanilla calls removeBlock unconditionally if no pull is attempted.
                        // This DOES trigger neighbor updates at the head position.
                        removeHeadBlock(headPos);
                    } else {
                        // Vanilla delegates to moveBlocks(..., false).
                        // It silently clears the PISTON_HEAD with flag 20 (no neighbor updates).
                        BlockState headStateBeforeResolve = getBlockState(headPos);

                        if (headStateBeforeResolve.is(Blocks.PISTON_HEAD)) {
                            log("§7[Vanilla moveBlocks] Clearing PISTON_HEAD quietly before resolver (Flag 20)");
                            setBlockRaw(headPos, Blocks.AIR.defaultBlockState());
                            // We explicitly skip simulatePistonHeadOnRemove here.
                            // The base is already MOVING_PISTON, so no base destruction occurs,
                            // and flag 20 suppresses all block removal neighbor updates.
                        }

                        SimPistonResolver resolver = new SimPistonResolver(this, pos, facing, false);

                        if (resolver.resolve()) {
                            moveBlocks(pos, currentState, facing, false, resolver);
                        } else {
                            log("§cRetract pull failed: " + resolver.getFailureReason());
                            // Nothing else happens.
                            // The Piston Head stays deleted, Obsidian stays put, and crucially:
                            // NO neighbor updates are fired from headPos.
                        }
                    }
                }
            } else {
                // Normal piston
                removeHeadBlock(headPos);
            }
        }
    }
    private void removeHeadBlock(BlockPos headPos) {
        BlockState headState = getBlockState(headPos);
        if (!headState.isAir()) {
            if (headState.getBlock() instanceof PistonHeadBlock) {
                log("§c[Instant Delete] PISTON_HEAD facing "
                        + headState.getValue(PistonHeadBlock.FACING).getName().toUpperCase()
                        + " at " + headPos.toShortString());
            } else {
                log("§c[Instant Delete] Block " + headState.getBlock().getName().getString() + " at " + headPos.toShortString());
            }
            setBlock(headPos, Blocks.AIR.defaultBlockState());

            if (headState.getBlock() instanceof PistonHeadBlock) {
                simulatePistonHeadOnRemove(headPos, headState);
            }
            updateNeighborsAt(headPos);
        }

    }
    private void moveBlocks(BlockPos pistonPos, BlockState pistonState, Direction dir, boolean extending, SimPistonResolver resolver) {
        List<BlockPos> toPush = resolver.toPush;
        List<BlockPos> toDestroy = resolver.toDestroy;

        log("Resolver found " + toPush.size() + " blocks to move, " + toDestroy.size() + " blocks to destroy.");

        for (int i = toDestroy.size() - 1; i >= 0; i--) {
            BlockPos p = toDestroy.get(i);
            setBlock(p, Blocks.AIR.defaultBlockState());
        }

        Map<BlockPos, BlockState> vacatedSpots = new HashMap<>();
        for (BlockPos p : toPush) {
            vacatedSpots.put(p, getBlockState(p));
        }

        Direction moveDir = extending ? dir : dir.getOpposite();
        BlockPos headPos = pistonPos.relative(dir);

        if (!extending && getBlockState(headPos).is(Blocks.PISTON_HEAD)) {
            setBlockRaw(headPos, Blocks.AIR.defaultBlockState());
        }

        for (int i = toPush.size() - 1; i >= 0; i--) {
            BlockPos oldPos = toPush.get(i);
            BlockPos newPos = oldPos.relative(moveDir);
            BlockState movingState = vacatedSpots.get(oldPos);

            vacatedSpots.remove(newPos);

            PistonType type = pistonState.is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT;
            SimPistonMovingEntity be = new SimPistonMovingEntity(newPos, movingState, dir, extending, false);
            blockEntities.put(newPos, be);

            setBlockRaw(newPos, Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, dir).setValue(PistonHeadBlock.TYPE, type));
            //log("Created MovingPiston BE at " + newPos.toShortString() + " carrying " + movingState.getBlock().getName().getString());
            fireShapeUpdates(newPos);
        }

        if (extending) {
            PistonType type = pistonState.is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT;
            BlockState headState = Blocks.PISTON_HEAD.defaultBlockState()
                    .setValue(PistonHeadBlock.FACING, dir)
                    .setValue(PistonHeadBlock.TYPE, type);

            vacatedSpots.remove(headPos);
            SimPistonMovingEntity headBE = new SimPistonMovingEntity(headPos, headState, dir, true, true);
            blockEntities.put(headPos, headBE);
            setBlockRaw(headPos, Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, dir).setValue(PistonHeadBlock.TYPE, type));
            //log("Created MovingPiston BE at " + headPos.toShortString() + " carrying PISTON_HEAD");
            fireShapeUpdates(headPos);
        }

        for (BlockPos p : vacatedSpots.keySet()) {
            setBlockRaw(p, Blocks.AIR.defaultBlockState());
            fireShapeUpdates(p);
        }

        for (int i = toPush.size() - 1; i >= 0; i--) {
            //log("Firing neighbor updates for vacated pushed pos: " + toPush.get(i).toShortString());
            updateNeighborsAt(toPush.get(i));
        }
        if (extending) {
            //log("Firing neighbor updates for moving piston head starting pos: " + headPos.toShortString());
            updateNeighborsAt(headPos);
        }
    }

    private void simulatePistonHeadOnRemove(BlockPos headPos, BlockState headState) {
        Direction headFacing = headState.getValue(PistonHeadBlock.FACING);
        PistonType headType  = headState.getValue(PistonHeadBlock.TYPE);
        BlockPos   basePos   = headPos.relative(headFacing.getOpposite());
        BlockState baseState = getBlockState(basePos);

        // Replicate PistonHeadBlock.isFittingBase (private in vanilla):
        //   Block expected = type == DEFAULT ? Blocks.PISTON : Blocks.STICKY_PISTON;
        //   return base.is(expected) && base[EXTENDED] && base[FACING] == head[FACING];
        Block expectedBase = headType == PistonType.DEFAULT ? Blocks.PISTON : Blocks.STICKY_PISTON;
        boolean fittingBase = baseState.is(expectedBase)
                && baseState.getValue(PistonBaseBlock.EXTENDED)
                && baseState.getValue(PistonBaseBlock.FACING) == headFacing;

        if (fittingBase) {
            log("§c[onRemove] isFittingBase=true — "
                    + (headType == PistonType.STICKY ? "sticky " : "")
                    + "base still EXTENDED at " + basePos.toShortString() + ", destroying it");
            setBlock(basePos, Blocks.AIR.defaultBlockState());
            log("§d[onRemove] Neighbour updates from base destruction at " + basePos.toShortString() + ":");
            for (Direction dir : UPDATE_ORDER) {
                //log("§d    " + dir.getName().toUpperCase() + " → " + basePos.relative(dir).toShortString());
            }
            updateNeighborsAt(basePos);
        } else {
            log("§7[onRemove] isFittingBase=false — "
                    + baseState.getBlock().getName().getString()
                    + " at " + basePos.toShortString() + " is not a fitting base, no further deletion");
        }

        // Enumerate the six positions that updateNeighborsAt(headPos) will touch
        // later in the retraction flow. Printed here so they appear adjacent to
        // the instant-delete log rather than buried later in the output.
        log("§d[onRemove] Neighbour updates from PISTON_HEAD deletion at " + headPos.toShortString() + ":");
        for (Direction dir : UPDATE_ORDER) {
            //log("§d    " + dir.getName().toUpperCase() + " → " + headPos.relative(dir).toShortString());
        }
    }

    private void clearHeadPos(BlockPos headPos) {
        boolean wasAir = getBlockState(headPos).isAir();
        setBlockRaw(headPos, Blocks.AIR.defaultBlockState());
        if (!wasAir) {
            log("Set " + headPos.toShortString() + " to Air (cleared head)");
            log("Firing neighbor updates for cleared head at: " + headPos.toShortString());
            updateNeighborsAt(headPos);
        }
    }

    private boolean isPushableForPull(BlockState state, BlockPos pos, Direction pushDir, Direction pistonFacing) {
        if (blockEntities.containsKey(pos)) return false;
        if (state.isAir() || state.is(Blocks.BEDROCK)) return false;
        if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)
                || state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.REINFORCED_DEEPSLATE)
                || state.is(Blocks.BEDROCK)) return false;
        if (!state.is(Blocks.PISTON) && !state.is(Blocks.STICKY_PISTON)) {
            switch (state.getPistonPushReaction()) {
                case BLOCK:     return false;
                case DESTROY:   return false;
                case PUSH_ONLY: return pushDir == pistonFacing;
            }
        } else {
            if (state.getValue(PistonBaseBlock.EXTENDED)) return false;
        }
        return !state.hasBlockEntity();
    }
}