package com.pingu.slimestone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.material.PushReaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * All piston extend/retract simulation for a {@link VirtualLevel}.
 * <p>
 * This class is the home for:
 * <ul>
 *   <li>Deciding whether a piston should extend or retract
 *       ({@link #checkIfExtend}) and queuing the corresponding block event.</li>
 *   <li>Executing queued extend/retract events
 *       ({@link #triggerPistonEvent}), including resolving pushes/pulls via
 *       {@link SimPistonResolver} and moving blocks via {@link #moveBlocks}.</li>
 *   <li>Piston head survival checks on neighbor updates
 *       ({@link #handlePistonHeadNeighborUpdate}).</li>
 *   <li>Instant deletion / onRemove emulation for piston heads
 *       ({@link #removeHeadBlock}, {@link #simulatePistonHeadOnRemove}).</li>
 * </ul>
 * It operates entirely on the shared state of its {@link VirtualLevel}
 * (block grid, block-entity map, block-event queue, logging, and the
 * neighbor/shape update plumbing) rather than holding any of its own.
 */
public class PistonMechanics {

    private final VirtualLevel level;

    public PistonMechanics(VirtualLevel level) {
        this.level = level;
    }

    /**
     * Mirrors the piston-base half of {@code PistonBaseBlock.neighborChanged}:
     * if the piston is powered but not extended and the push path resolves
     * cleanly, queue an Extend event; if it's unpowered but extended, queue a
     * Retract event. Duplicate events for the same block event are ignored,
     * matching the {@code blockEvents.contains(event)} guard.
     */
    public void checkIfExtend(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(PistonBaseBlock.FACING);
        boolean powered = level.hasRedstoneBlockPower(pos, facing);
        boolean extended = state.getValue(PistonBaseBlock.EXTENDED);

        if (powered && !extended) {
            SimPistonResolver resolver = new SimPistonResolver(level, pos, facing, true);
            if (resolver.resolve()) {
                level.log("Piston powered and path valid at " + pos.toShortString() + ". Queuing Extend.");
                BlockEvent event = new BlockEvent(pos, state, 0, facing);
                if (!level.blockEvents.contains(event)) {
                    level.blockEvents.add(event);
                    // REMOVED: PistonDebugger.logExpected(...)
                }
            }
        } else if (!powered && extended) {
            level.log("Piston unpowered at " + pos.toShortString() + ". Queuing Contract Event.");
            BlockEvent event = new BlockEvent(pos, state, 1, facing);
            if (!level.blockEvents.contains(event)) {
                level.blockEvents.add(event);
                // REMOVED: PistonDebugger.logExpected(...)
            }
        }
    }
    private void fireObserverRemovalUpdate(BlockPos pos, BlockState oldState) {
        if (oldState == null) return;
        if (!oldState.is(Blocks.OBSERVER)) return;

        // Condition 1 – observer must be in its powered/lit state.
        if (!oldState.getValue(BlockStateProperties.POWERED)) return;

        // Condition 2 – the turn-off tick must still be pending, confirming the
        // observer is in its live pulse window (powered on, but not yet ticked off).
        // Mirrors affectNeighborsAfterRemoval's hasScheduledTick gate.
        // Note: toDestroy blocks never need this path — observers are movable, so
        // the resolver always puts them in toPush, never toDestroy. We only call
        // this for pushed blocks (toPush loop below), matching vanilla's behaviour.
        if (!level.hasScheduledTick(pos, Blocks.OBSERVER)) return;

        BlockPos outputPos = pos.relative(oldState.getValue(BlockStateProperties.FACING).getOpposite());
        level.log("§b[Observer] Powered observer removed at " + pos.toShortString()
                + ", instant update → " + outputPos.toShortString());
        level.updateNeighborsFromObserver(pos, oldState);
    }
    /**
     * Mirrors the piston-head half of {@code PistonHeadBlock.neighborChanged}
     * (via {@code canSurvive}): if the head no longer has a fitting,
     * extended base (and isn't sitting on a MOVING_PISTON mid-animation), it
     * is destroyed. Otherwise the update is forwarded to the base so it can
     * re-evaluate whether it should extend/retract.
     */
    public void handlePistonHeadNeighborUpdate(BlockPos pos, BlockState state) {
        Direction facing = state.getValue(PistonHeadBlock.FACING);
        BlockPos basePos = pos.relative(facing.getOpposite());
        BlockState baseState = level.getBlockState(basePos);

        // 1. Emulate vanilla PistonHeadBlock.canSurvive()
        PistonType headType = state.getValue(PistonHeadBlock.TYPE);
        Block expectedBase = headType == PistonType.DEFAULT ? Blocks.PISTON : Blocks.STICKY_PISTON;

        boolean isFittingBase = baseState.is(expectedBase)
                && baseState.getValue(PistonBaseBlock.EXTENDED)
                && baseState.getValue(PistonBaseBlock.FACING) == facing;

        boolean isMovingBase = baseState.is(Blocks.MOVING_PISTON)
                && baseState.getValue(BlockStateProperties.FACING) == facing;

        if (!isFittingBase && !isMovingBase) {
            level.log("§c[Survival] PISTON_HEAD at " + pos.toShortString() + " lost its valid base, destroying it");
            level.setBlock(pos, Blocks.AIR.defaultBlockState());
            level.log("§d[Survival] Firing neighbor updates for destroyed PISTON_HEAD at: " + pos.toShortString());
            level.updateNeighborsAt(pos);
            return; // Stop processing since the head is now Air
        }

        // 2. If it survives, pass the update to the base (emulating vanilla neighborChanged)
        if (baseState.getBlock() instanceof PistonBaseBlock) {
            checkIfExtend(basePos, baseState);
        }
    }

    /**
     * Executes a queued Extend (type 0) or Retract (type 1) block event,
     * re-validating power/variant state first since the event may have gone
     * stale between being queued and being drained.
     */
    public void triggerPistonEvent(BlockPos pos, BlockState queuedState, int type, Direction queuedFacing) {
        BlockState currentState = level.getBlockState(pos);

        if (!currentState.is(queuedState.getBlock())) {
            level.log("§eEvent cancelled: Block at " + pos.toShortString() + " is no longer the correct piston variant.");
            return;
        }

        Direction facing = currentState.getValue(PistonBaseBlock.FACING);
        boolean isPowered = level.hasRedstoneBlockPower(pos, facing);
        if (type == 0) {
            if (!isPowered) {
                level.log("§eExtend cancelled: piston lost power before processing at " + pos.toShortString());
                return;
            }

            SimPistonResolver resolver = new SimPistonResolver(level, pos, facing, true);
            if (!resolver.resolve()) {
                level.log("§cExtend failed: " + resolver.getFailureReason() + " (Pos: " +
                        (resolver.getFailurePos() != null ? resolver.getFailurePos().toShortString() : "N/A") + ")");
                return;
            }

            PistonDebugger.logExpected(pos, true, level.getCurrentTick());

            moveBlocks(pos, currentState, facing, true, resolver);
            boolean extendIsSticky = currentState.getBlock() == Blocks.STICKY_PISTON;
            level.setBlockRaw(pos, currentState.setValue(PistonBaseBlock.EXTENDED, true));
            level.fireShapeUpdates(pos);

            level.log("Set block at " + pos.toShortString() + " to "
                    + (extendIsSticky ? "Sticky Piston Headless Base" : "Piston Headless Base"));
            //log("Firing neighbor updates for piston base at: " + pos.toShortString());
            level.updateNeighborsAt(pos);
        } else if (type == 1) {
            if (isPowered) {
                level.log("§eRetract cancelled: piston re-gained power at " + pos.toShortString());
                level.setBlockRaw(pos, currentState.setValue(PistonBaseBlock.EXTENDED, true));
                return;
            }

            PistonDebugger.logExpected(pos, false, level.getCurrentTick());

            boolean isSticky = currentState.getBlock() == Blocks.STICKY_PISTON;
            BlockPos headPos = pos.relative(facing);

            // 1. Unconditionally final-tick ANY moving block at the head position (Vanilla Step 1)
            SimPistonMovingEntity headBE = level.blockEntities.get(headPos);
            if (headBE != null) {
                level.log("Instant finalTick on moving head at " + headPos.toShortString());
                headBE.finalTick(level);
            }

            // 2. Base transitions to MOVING_PISTON
            PistonType pType = isSticky ? PistonType.STICKY : PistonType.DEFAULT;
            BlockState movingBaseState = Blocks.MOVING_PISTON.defaultBlockState()
                    .setValue(BlockStateProperties.FACING, facing)
                    .setValue(PistonHeadBlock.TYPE, pType);
            BlockState unextendedBase = currentState.setValue(PistonBaseBlock.EXTENDED, false);

            level.setBlockRaw(pos, movingBaseState);
            level.blockEntities.put(pos, new SimPistonMovingEntity(pos, unextendedBase, facing, false, true));
            MovingBlockDebugger.logExpected(pos, unextendedBase, false, level.getCurrentTick());
            level.updateNeighborsAt(pos);
            level.fireShapeUpdates(pos); // Add this line
            level.log("Created MovingPiston BE at " + pos.toShortString() + " for Retracting "
                    + (isSticky ? "Sticky " : "") + "Base");
            //log("Firing neighbor updates for retracting base at: " + pos.toShortString());

            if (isSticky) {
                BlockPos twoAheadPos = pos.relative(facing, 2);
                SimPistonMovingEntity twoAheadBE = level.blockEntities.get(twoAheadPos);
                boolean instantFinished = false;

                if (twoAheadBE != null && twoAheadBE.extending && twoAheadBE.direction == facing) {
                    level.log("Instant finalTick on mid-push block at " + twoAheadPos.toShortString());
                    twoAheadBE.finalTick(level);
                    instantFinished = true;
                }

                if (!instantFinished) {
                    BlockState twoAheadState = level.getBlockState(twoAheadPos);
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
                        BlockState headStateBeforeResolve = level.getBlockState(headPos);

                        if (headStateBeforeResolve.is(Blocks.PISTON_HEAD)) {
                            level.log("§7[Vanilla moveBlocks] Clearing PISTON_HEAD quietly before resolver (Flag 20)");
                            level.setBlockRaw(headPos, Blocks.AIR.defaultBlockState());
                            // We explicitly skip simulatePistonHeadOnRemove here.
                            // The base is already MOVING_PISTON, so no base destruction occurs,
                            // and flag 20 suppresses all block removal neighbor updates.
                        }

                        SimPistonResolver resolver = new SimPistonResolver(level, pos, facing, false);

                        if (resolver.resolve()) {
                            moveBlocks(pos, currentState, facing, false, resolver);
                        } else {
                            level.log("§cRetract pull failed: " + resolver.getFailureReason());
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

    /**
     * Instantly deletes the block at {@code headPos} (normally a
     * PISTON_HEAD), running its onRemove emulation and firing neighbor
     * updates afterward.
     */
    private void removeHeadBlock(BlockPos headPos) {
        BlockState headState = level.getBlockState(headPos);
        if (!headState.isAir()) {
            if (headState.getBlock() instanceof PistonHeadBlock) {
                level.log("§c[Instant Delete] PISTON_HEAD facing "
                        + headState.getValue(PistonHeadBlock.FACING).getName().toUpperCase()
                        + " at " + headPos.toShortString());
            } else {
                level.log("§c[Instant Delete] Block " + headState.getBlock().getName().getString() + " at " + headPos.toShortString());
            }
            level.setBlock(headPos, Blocks.AIR.defaultBlockState());

            if (headState.getBlock() instanceof PistonHeadBlock) {
                simulatePistonHeadOnRemove(headPos, headState);
            }
            level.updateNeighborsAt(headPos);
        }

    }

    /**
     * Carries out a resolved push (extend) or pull (retract): destroys
     * blocks the resolver marked for destruction, shifts pushed blocks into
     * MOVING_PISTON placeholders backed by {@link SimPistonMovingEntity}s,
     * places the moving piston head when extending, clears vacated spots,
     * and fires the appropriate shape/neighbor updates.
     */
    private void moveBlocks(BlockPos pistonPos, BlockState pistonState, Direction dir, boolean extending, SimPistonResolver resolver) {
        List<BlockPos> toPush = resolver.toPush;
        List<BlockPos> toDestroy = resolver.toDestroy;

        level.log("Resolver found " + toPush.size() + " blocks to move, " + toDestroy.size() + " blocks to destroy.");

        for (int i = toDestroy.size() - 1; i >= 0; i--) {
            BlockPos p = toDestroy.get(i);
            level.setBlock(p, Blocks.AIR.defaultBlockState());
        }

        Map<BlockPos, BlockState> vacatedSpots = new HashMap<>();
        for (BlockPos p : toPush) {
            vacatedSpots.put(p, level.getBlockState(p));
        }
        Map<BlockPos, BlockState> oldPushStates = new HashMap<>(vacatedSpots);

        Direction moveDir = extending ? dir : dir.getOpposite();
        BlockPos headPos = pistonPos.relative(dir);

        if (!extending && level.getBlockState(headPos).is(Blocks.PISTON_HEAD)) {
            level.setBlockRaw(headPos, Blocks.AIR.defaultBlockState());
        }

        for (int i = toPush.size() - 1; i >= 0; i--) {
            BlockPos oldPos = toPush.get(i);
            BlockPos newPos = oldPos.relative(moveDir);
            BlockState movingState = vacatedSpots.get(oldPos);

            vacatedSpots.remove(newPos);

            PistonType type = pistonState.is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT;
            SimPistonMovingEntity be = new SimPistonMovingEntity(newPos, movingState, dir, extending, false);
            level.blockEntities.put(newPos, be);
            MovingBlockDebugger.logExpected(newPos, movingState, extending, level.getCurrentTick());

            level.setBlockRaw(newPos, Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, dir).setValue(PistonHeadBlock.TYPE, type));
            //log("Created MovingPiston BE at " + newPos.toShortString() + " carrying " + movingState.getBlock().getName().getString());
            level.fireShapeUpdates(newPos);
        }

        if (extending) {
            PistonType type = pistonState.is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT;
            BlockState headState = Blocks.PISTON_HEAD.defaultBlockState()
                    .setValue(PistonHeadBlock.FACING, dir)
                    .setValue(PistonHeadBlock.TYPE, type);

            vacatedSpots.remove(headPos);
            SimPistonMovingEntity headBE = new SimPistonMovingEntity(headPos, headState, dir, true, true);
            level.blockEntities.put(headPos, headBE);
            level.setBlockRaw(headPos, Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, dir).setValue(PistonHeadBlock.TYPE, type));
            //log("Created MovingPiston BE at " + headPos.toShortString() + " carrying PISTON_HEAD");
            MovingBlockDebugger.logExpected(headPos, headState, true, level.getCurrentTick());
            level.fireShapeUpdates(headPos);
        }

        for (BlockPos p : vacatedSpots.keySet()) {
            level.setBlockRaw(p, Blocks.AIR.defaultBlockState());
            level.fireShapeUpdates(p);
        }

        for (int i = toPush.size() - 1; i >= 0; i--) {
            //log("Firing neighbor updates for vacated pushed pos: " + toPush.get(i).toShortString());
            level.updateNeighborsAt(toPush.get(i));
        }
        for (int i = toPush.size() - 1; i >= 0; i--) {
            fireObserverRemovalUpdate(toPush.get(i), oldPushStates.get(toPush.get(i)));
        }
        if (extending) {
            //log("Firing neighbor updates for moving piston head starting pos: " + headPos.toShortString());
            level.updateNeighborsAt(headPos);
        }
    }

    /**
     * Mirrors {@code PistonHeadBlock.onRemove}/{@code isFittingBase}: if the
     * base behind the just-deleted head is still a fully-extended, matching
     * piston base, that base is also destroyed (and its destruction's
     * neighbor updates fired). Otherwise nothing further happens.
     */

    private void simulatePistonHeadOnRemove(BlockPos headPos, BlockState headState) {
        Direction headFacing = headState.getValue(PistonHeadBlock.FACING);
        PistonType headType  = headState.getValue(PistonHeadBlock.TYPE);
        BlockPos   basePos   = headPos.relative(headFacing.getOpposite());
        BlockState baseState = level.getBlockState(basePos);

        // Replicate PistonHeadBlock.isFittingBase (private in vanilla):
        //   Block expected = type == DEFAULT ? Blocks.PISTON : Blocks.STICKY_PISTON;
        //   return base.is(expected) && base[EXTENDED] && base[FACING] == head[FACING];
        Block expectedBase = headType == PistonType.DEFAULT ? Blocks.PISTON : Blocks.STICKY_PISTON;
        boolean fittingBase = baseState.is(expectedBase)
                && baseState.getValue(PistonBaseBlock.EXTENDED)
                && baseState.getValue(PistonBaseBlock.FACING) == headFacing;

        if (fittingBase) {
            level.log("§c[onRemove] isFittingBase=true — "
                    + (headType == PistonType.STICKY ? "sticky " : "")
                    + "base still EXTENDED at " + basePos.toShortString() + ", destroying it");
            level.setBlock(basePos, Blocks.AIR.defaultBlockState());
            level.log("§d[onRemove] Neighbour updates from base destruction at " + basePos.toShortString() + ":");
            for (Direction dir : VirtualLevel.UPDATE_ORDER) {
                //log("§d    " + dir.getName().toUpperCase() + " → " + basePos.relative(dir).toShortString());
            }
            level.updateNeighborsAt(basePos);
        } else {
            level.log("§7[onRemove] isFittingBase=false — "
                    + baseState.getBlock().getName().getString()
                    + " at " + basePos.toShortString() + " is not a fitting base, no further deletion");
        }

        // Enumerate the six positions that updateNeighborsAt(headPos) will touch
        // later in the retraction flow. Printed here so they appear adjacent to
        // the instant-delete log rather than buried later in the output.
        level.log("§d[onRemove] Neighbour updates from PISTON_HEAD deletion at " + headPos.toShortString() + ":");
        for (Direction dir : VirtualLevel.UPDATE_ORDER) {
            //log("§d    " + dir.getName().toUpperCase() + " → " + headPos.relative(dir).toShortString());
        }
    }

    /**
     * Mirrors the private {@code PistonBaseBlock.isPushable} check used when
     * deciding whether a sticky piston's retraction should pull the block two
     * ahead of it back into place.
     */
    private boolean isPushableForPull(BlockState state, BlockPos pos, Direction pushDir, Direction pistonFacing) {
        if (level.blockEntities.containsKey(pos)) return false;
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