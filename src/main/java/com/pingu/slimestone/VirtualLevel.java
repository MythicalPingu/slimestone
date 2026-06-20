package com.pingu.slimestone;
import net.minecraft.world.level.block.Block;
// ObserverDebugger is in the same package — no extra import needed.
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

import java.util.*;


public class VirtualLevel {

    public static final int UPDATE_NEIGHBORS = 1;
    public static final int UPDATE_CLIENTS = 2;
    public static final int UPDATE_INVISIBLE = 4;
    public static final int UPDATE_KNOWN_SHAPE = 16;
    public static final int UPDATE_SUPPRESS_DROPS = 32;
    public static final int UPDATE_MOVED_BY_PISTON = 64;
    public static final int UPDATE_ALL = UPDATE_NEIGHBORS | UPDATE_CLIENTS;

    // Package-private so PistonMechanics can reuse the same update ordering
    static final Direction[] UPDATE_ORDER = new Direction[]{Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};

    private final ServerPlayer player;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();
    // Maps a block's current (possibly-displaced) position back to the real-world
// position it originally started at, before any simulated pushes/pulls.
    private final Map<BlockPos, BlockPos> originTracker = new HashMap<>();

    /** Returns the real-world starting position for a block now at {@code current}. */
    public BlockPos getOriginPos(BlockPos current) {
        return originTracker.getOrDefault(current.immutable(), current.immutable());
    }

    void recordDisplacement(BlockPos oldPos, BlockPos newPos) {
        BlockPos origin = getOriginPos(oldPos);
        originTracker.remove(oldPos.immutable());
        if (origin.equals(newPos)) {
            originTracker.remove(newPos.immutable()); // moved back home, stop tracking
        } else {
            originTracker.put(newPos.immutable(), origin);
        }
    }
    // All piston extend/retract simulation lives here
    public final PistonMechanics pistonMechanics;

    private final Set<BlockPos> activatedObservers = new HashSet<>();
    private final Set<BlockPos> activatedPistons = new HashSet<>();
    private final List<PendingDisplay> pendingObserverDisplays = new ArrayList<>();
    private final List<PendingDisplay> pendingPistonDisplays = new ArrayList<>();

    public static class PendingDisplay {
        public final BlockPos pos;
        public final String extraText;

        public PendingDisplay(BlockPos pos, String extraText) {
            this.pos = pos;
            this.extraText = extraText;
        }
    }

    // Package-private so the Resolver can verify moving pistons
    final Map<BlockPos, SimPistonMovingEntity> blockEntities = new LinkedHashMap<>();

    // Package-private so PistonMechanics can queue/check extend & retract events
    final Queue<BlockEvent> blockEvents = new ArrayDeque<>();
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
        this.pistonMechanics = new PistonMechanics(this);
    }
    public void queueObserverDisplay(BlockPos pos) {
        BlockPos displayPos = getOriginPos(pos);
        if (activatedObservers.add(displayPos.immutable())) {
            pendingObserverDisplays.add(new PendingDisplay(displayPos.immutable(), ""));
        }
    }

    public void queuePistonDisplay(BlockPos pos, int blockCount) {
        BlockPos displayPos = getOriginPos(pos);
        if (activatedPistons.add(displayPos.immutable())) {
            pendingPistonDisplays.add(new PendingDisplay(displayPos.immutable(), "\nPl " + blockCount));
        }
    }

    private void flushDisplays(List<PendingDisplay> displays, int tick) {
        if (displays.isEmpty()) return;
        boolean useSuffix = displays.size() > 1; // Only use a,b,c if more than 1 activated

        for (int i = 0; i < displays.size(); i++) {
            PendingDisplay d = displays.get(i);
            String suffix = useSuffix ? String.valueOf((char) ('a' + i)) : "";
            String text = "Gt " + tick + suffix + d.extraText;
            spawnGtDisplay(d.pos, text);
        }
        displays.clear();
    }

    private void spawnGtDisplay(BlockPos targetPos, String jsonText) {
        double x = targetPos.getX() + 0.5;
        double y = targetPos.getY() + 0.5;
        double z = targetPos.getZ() + 0.5;

        String summon = String.format(
                java.util.Locale.US,
                "summon minecraft:text_display %.3f %.3f %.3f {text:'%s',billboard:\"center\",see_through:1b,shadow:0b,default_background:1b,alignment:\"center\",line_width:200}",
                x, y, z, jsonText
        );

        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack(),
                summon
        );
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
        //log("Set block at " + pos.toShortString() + " to " + state.getBlock().getName().getString());

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
            //log("§8[Vanilla Mechanics] Updates suppressed at " + pos.toShortString() + " because onPlace modified the state.");
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

                //log("§3[Observer] onPlace reset spurious POWERED state at " + pos.toShortString());
                updateNeighborsFromObserver(pos, unpowered);
            }
        }
        if (state.getBlock() instanceof PistonBaseBlock) {
            pistonMechanics.checkIfExtend(pos, state);
        }
    }

    private void evaluateOwnShape(BlockPos pos, BlockState state) {
        // Emulates a placed block running its own shape checks against neighbors upon landing
        if (state.is(Blocks.OBSERVER)) {
            if (!state.getValue(BlockStateProperties.POWERED) && !hasScheduledTick(pos, Blocks.OBSERVER)) {
                //log("§3[Schedule] Observer at " + pos.toShortString() + " → fires next tick");
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

    public void scheduleTick(BlockPos pos, Block type, int delay) {
        if (hasScheduledTick(pos, type)) return;
        long fireTick = currentTick + delay;
        scheduledBlockTicks.add(new SimScheduledTick(pos.immutable(), type, fireTick, subTickOrderCounter++));

    }

    public void runTickLoop(int maxTicks) {

        for (currentTick = 0; currentTick < maxTicks; currentTick++) {
            boolean active = false;


            List<SimScheduledTick> currentlyTicking = new ArrayList<>();
            while (!scheduledBlockTicks.isEmpty() && scheduledBlockTicks.peek().triggerTick() <= currentTick) {
                currentlyTicking.add(scheduledBlockTicks.poll());
            }

            for (SimScheduledTick tick : currentlyTicking) {
                active = true;
                processScheduledTick(tick);
                flushNeighborUpdates();
            }

            // FLUSH OBSERVER LABELS HERE
            flushDisplays(pendingObserverDisplays, currentTick);

            // ── PHASE 2: BLOCK EVENTS ─────────────────────────────────────────
            currentPhase = "BLOCK EVENTS";
            while (!blockEvents.isEmpty()) {
                active = true;
                BlockEvent event = blockEvents.peek();
                pistonMechanics.triggerPistonEvent(event.pos(), event.state(), event.type(), event.dir());
                flushNeighborUpdates();
                blockEvents.poll();
            }

            // FLUSH PISTON LABELS HERE
            flushDisplays(pendingPistonDisplays, currentTick);

            // ── PHASE 3: BLOCK ENTITIES ────────────────────────────────────────
            currentPhase = "BLOCK ENTITIES";
            List<SimPistonMovingEntity> tickingBEs = new ArrayList<>(blockEntities.values());
            for (SimPistonMovingEntity be : tickingBEs) {
                active = true;
                be.tick(this);
                flushNeighborUpdates();
            }



            if (!active && blockEvents.isEmpty() && neighborUpdates.isEmpty() && scheduledBlockTicks.isEmpty()) {
                //log("§8Simulation settled. Halting early.");
                break;
            }
        }
    }
    public int getCurrentTick() {
        return currentTick;
    }
    private void processScheduledTick(SimScheduledTick tick) {
        BlockState state = getBlockState(tick.pos());

        if (!state.is(tick.type())) {
            //log("§8[Tick] Cancelled stale " + tick.type().getName().getString()
            //        + " tick at " + tick.pos().toShortString() + " (block changed)");
            return;
        }

        if (tick.type() == Blocks.OBSERVER) {
            processObserverTick(tick.pos(), state);
        } else if (tick.type() == Blocks.REDSTONE_LAMP) {
            processRedstoneLampTick(tick.pos(), state);
        }
    }

    private void processObserverTick(BlockPos pos, BlockState state) {
        boolean wasPowered = state.getValue(BlockStateProperties.POWERED);

        if (wasPowered) {
            BlockState unpowered = state.setValue(BlockStateProperties.POWERED, false);
            setBlockRaw(pos, unpowered);
            //log("§3[Observer] " + pos.toShortString() + " → POWERED=false (pulse end)");
            ObserverDebugger.logExpected(pos, false, currentTick);

            // 1. Emit Shape Updates to immediate neighbors
            fireShapeUpdates(pos);
            // 2. Emit Redstone Updates to the back block and its neighbors
            updateNeighborsFromObserver(pos, unpowered);
        } else {
            BlockState powered = state.setValue(BlockStateProperties.POWERED, true);
            setBlockRaw(pos, powered);
            //log("§3[Observer] " + pos.toShortString() + " → POWERED=true (pulse start)");
            ObserverDebugger.logExpected(pos, true, currentTick);

            // --- NEW: Display the GT if it's the first time ---
            queueObserverDisplay(pos);
            // --------------------------------------------------

            fireShapeUpdates(pos);
            scheduleTick(pos, Blocks.OBSERVER, 2);

            updateNeighborsFromObserver(pos, powered);
        }
    }

    void updateNeighborsFromObserver(BlockPos obsPos, BlockState obsState) {
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

        // --- REDSTONE LAMP ---
        else if (state.is(Blocks.REDSTONE_LAMP)) {
            handleRedstoneLampNeighborChanged(pos, state);
        }

        // --- TRAPDOOR ---
        else if (state.getBlock() instanceof TrapDoorBlock) {
            handleTrapDoorNeighborChanged(pos, state);
        }

        // --- PISTON LOGIC (delegated to PistonMechanics) ---
        else if (state.getBlock() instanceof PistonBaseBlock) {
            pistonMechanics.checkIfExtend(pos, state);
        } else if (state.getBlock() instanceof PistonHeadBlock) {
            pistonMechanics.handlePistonHeadNeighborUpdate(pos, state);
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
                //log("§6[ShapeUpdate] Detected update from " + dir.getOpposite().getName() + " side at " + neighborPos.toShortString());

                Direction facing = state.getValue(BlockStateProperties.FACING);
                BlockPos lookingAt = neighborPos.relative(facing);

                if (lookingAt.equals(pos)) {
                    // This block matches vanilla's startSignal behavior
                    if (!state.getValue(BlockStateProperties.POWERED) && !hasScheduledTick(neighborPos, Blocks.OBSERVER)) {
                        //log("§3[Schedule] Observer at " + neighborPos.toShortString() + " → fires next tick");
                        scheduleTick(neighborPos, Blocks.OBSERVER, 2);
                    }
                }
            } else if (state.is(Blocks.NOTE_BLOCK)) {

                if (dir.getAxis() == Direction.Axis.Y) {
                    BlockState updated = simSetNoteBlockInstrument(neighborPos, state);
                    if (updated != state) {
                        setBlockRaw(neighborPos, updated);
                        //log("§a[NoteBlock] Instrument updated at " + neighborPos.toShortString()
                        //        + " → " + updated.getValue(BlockStateProperties.NOTEBLOCK_INSTRUMENT).name());
                    }
                }
                // Non-Y directions fall through super.updateShape, which is a no-op for NoteBlock.
            }
        }

        currentPhase = previousPhase;
    }

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
        if (state.getBlock() instanceof TrapDoorBlock) return false; // Trapdoors are not solid
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

    public boolean simHasNeighborSignal(BlockPos pos) {
        for (Direction dir : Direction.values()) {
            if (simHasSignal(pos.relative(dir), dir)) return true;
        }
        return false;
    }

    private void handleNoteBlockNeighborChanged(BlockPos pos, BlockState state) {
        boolean signal   = simHasNeighborSignal(pos);
        boolean wasPowered = state.getValue(BlockStateProperties.POWERED);
        if (signal == wasPowered) return;

        //log("§a[NoteBlock] " + pos.toShortString() + " → POWERED=" + signal);
        // flag 3 = UPDATE_ALL (UPDATE_NEIGHBORS | UPDATE_CLIENTS), matching vanilla
        setBlock(pos, state.setValue(BlockStateProperties.POWERED, signal), UPDATE_ALL);
    }

    private BlockState simSetNoteBlockInstrument(BlockPos pos, BlockState state) {
        NoteBlockInstrument above = getBlockState(pos.above()).instrument();
        if (above.worksAboveNoteBlock()) {
            return state.setValue(BlockStateProperties.NOTEBLOCK_INSTRUMENT, above);
        }
        NoteBlockInstrument below  = getBlockState(pos.below()).instrument();
        NoteBlockInstrument result = below.worksAboveNoteBlock() ? NoteBlockInstrument.HARP : below;
        return state.setValue(BlockStateProperties.NOTEBLOCK_INSTRUMENT, result);
    }


    // ─────────────────────────────────────────────────────────────────────────
    // REDSTONE LAMP
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors {@code RedstoneLampBlock.neighborChanged}.
     * <p>
     * Turn-on is immediate (flag 2 → shape updates, no neighbor updates).
     * Turn-off is deferred: a 4-tick scheduled tick is queued, and the tick
     * re-checks power before actually switching off.
     */
    private void handleRedstoneLampNeighborChanged(BlockPos pos, BlockState state) {
        boolean isLit  = state.getValue(BlockStateProperties.LIT);
        boolean signal = simHasNeighborSignal(pos);
        if (isLit == signal) return;

        if (isLit) {
            // Signal lost — schedule a delayed turn-off (vanilla: 4 game-ticks).
            // hasScheduledTick guard inside scheduleTick prevents duplicates.
            //log("§c[Lamp] " + pos.toShortString() + " → turn-off scheduled in 4t");
            scheduleTick(pos, Blocks.REDSTONE_LAMP, 4);
        } else {
            // Signal arrived — turn on immediately.
            //log("§c[Lamp] " + pos.toShortString() + " → LIT=true");
            // Flag 2 = UPDATE_CLIENTS: fires shape updates but NOT neighbor updates,
            // matching vanilla's setBlock(pos, state.cycle(LIT), 2).
            setBlock(pos, state.setValue(BlockStateProperties.LIT, true), UPDATE_CLIENTS);
        }
    }

    /**
     * Mirrors {@code RedstoneLampBlock.tick}.
     * <p>
     * Called 4 ticks after power was lost. Only turns off if the lamp is still
     * lit AND still has no signal — power may have returned during the delay.
     */
    private void processRedstoneLampTick(BlockPos pos, BlockState state) {
        if (state.getValue(BlockStateProperties.LIT) && !simHasNeighborSignal(pos)) {
            //log("§c[Lamp] " + pos.toShortString() + " → LIT=false (4t delay elapsed)");
            setBlock(pos, state.setValue(BlockStateProperties.LIT, false), UPDATE_CLIENTS);
        } else {
            //log("§8[Lamp] " + pos.toShortString() + " → turn-off tick no-op (still powered or already unlit)");
        }
    }


    // ─────────────────────────────────────────────────────────────────────────
    // TRAPDOOR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Mirrors {@code TrapDoorBlock.neighborChanged}.
     * <p>
     * All trapdoors are treated as iron trapdoors (redstone-only), so OPEN
     * always tracks POWERED. Vanilla calls
     * {@code level.setBlock(pos, newState, 2)} — flag 2 is UPDATE_CLIENTS,
     * which (in our setBlock) fires shape updates but NOT neighbor updates.
     */
    private void handleTrapDoorNeighborChanged(BlockPos pos, BlockState state) {
        boolean signal    = simHasNeighborSignal(pos);
        boolean wasPowered = state.getValue(BlockStateProperties.POWERED);
        if (signal == wasPowered) return;

        BlockState newState = state
                .setValue(BlockStateProperties.POWERED, signal)
                .setValue(BlockStateProperties.OPEN,    signal);

        //("§9[TrapDoor] " + pos.toShortString()
        //       + " → POWERED=" + signal + ", OPEN=" + signal);

        // Flag 2 = UPDATE_CLIENTS only.
        // setBlock will fire shape updates (bit 16 not set → shape updates run)
        // but NOT neighbor updates (bit 1 not set), matching vanilla exactly.
        setBlock(pos, newState, UPDATE_CLIENTS);
    }


    /**
     * Delegates to {@link PistonMechanics#checkIfExtend}. Kept on VirtualLevel
     * for backward compatibility with callers that previously invoked this
     * directly (e.g. {@code virtualLevel.checkIfExtend(pos, state)}).
     */
    public void checkIfExtend(BlockPos pos, BlockState state) {
        pistonMechanics.checkIfExtend(pos, state);
    }
}