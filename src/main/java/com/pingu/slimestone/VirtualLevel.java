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
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.material.PushReaction;

import java.util.*;

public class VirtualLevel {
    private static final Direction[] UPDATE_ORDER = new Direction[]{Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};

    private final ServerPlayer player;
    private final Map<BlockPos, BlockState> blocks = new HashMap<>();

    // Package-private so the Resolver can verify moving pistons
    final Map<BlockPos, SimPistonMovingEntity> blockEntities = new LinkedHashMap<>();

    private final Queue<BlockEvent> blockEvents = new ArrayDeque<>();
    private final Queue<NeighborUpdate> neighborUpdates = new ArrayDeque<>();

    private int currentTick = 0;
    private String currentPhase = "INIT";

    public VirtualLevel(ServerPlayer player) {
        this.player = player;
    }

    public void log(String msg) {
        String phaseColor = switch (currentPhase) {
            case "INIT" -> "§b";               // Aqua
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
        setBlockRaw(pos, state);
        log("Set block at " + pos.toShortString() + " to " + state.getBlock().getName().getString());
    }

    public void runTickLoop(int maxTicks) {
        for (currentTick = 0; currentTick < maxTicks; currentTick++) {
            boolean active = false;

            currentPhase = "BLOCK ENTITIES";
            List<SimPistonMovingEntity> tickingBEs = new ArrayList<>(blockEntities.values());
            for (SimPistonMovingEntity be : tickingBEs) {
                active = true;
                be.tick(this);
                flushNeighborUpdates();
            }

            currentPhase = "BLOCK EVENTS";
            while (!blockEvents.isEmpty()) {
                active = true;
                BlockEvent event = blockEvents.peek();
                triggerPistonEvent(event.pos(), event.state(), event.type(), event.dir());
                flushNeighborUpdates();
                blockEvents.poll();
            }

            if (!active && blockEvents.isEmpty() && neighborUpdates.isEmpty()) {
                log("§8Simulation settled. Halting early.");
                break;
            }
        }
    }

    private void flushNeighborUpdates() {
        String previousPhase = currentPhase;
        currentPhase = "NEIGHBOR UPDATES";
        while (!neighborUpdates.isEmpty()) {
            NeighborUpdate update = neighborUpdates.poll();
            BlockState state = getBlockState(update.pos());
            handlePistonUpdate(update.pos(), state);
        }
        currentPhase = previousPhase;
    }

    public void updateNeighborsAt(BlockPos pos) {
        String previousPhase = currentPhase;
        currentPhase = "NEIGHBOR UPDATES";

        for (Direction dir : UPDATE_ORDER) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState state = getBlockState(neighborPos);
            handlePistonUpdate(neighborPos, state);
        }

        currentPhase = previousPhase;
    }

    private void handlePistonUpdate(BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof PistonBaseBlock) {
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

    public boolean hasRedstoneBlockPower(BlockPos pos, Direction pistonFacing) {
        for (Direction dir : Direction.values()) {
            if (dir == pistonFacing) continue;
            if (getBlockState(pos.relative(dir)).is(Blocks.REDSTONE_BLOCK)) return true;
        }

        BlockPos above = pos.above();
        for (Direction dir : Direction.values()) {
            if (dir == Direction.DOWN) continue;
            if (getBlockState(above.relative(dir)).is(Blocks.REDSTONE_BLOCK)) return true;
        }

        return false;
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
        }
        updateNeighborsAt(headPos);
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
        }

        for (BlockPos p : vacatedSpots.keySet()) {
            setBlockRaw(p, Blocks.AIR.defaultBlockState());
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
                log("§d    " + dir.getName().toUpperCase() + " → " + basePos.relative(dir).toShortString());
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