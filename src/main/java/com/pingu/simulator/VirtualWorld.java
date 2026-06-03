package com.pingu.simulator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

import java.util.*;

public class VirtualWorld {

    public static final int CAPTURE_RADIUS = 64;

    private final Map<BlockPos, BlockState> blocks = new HashMap<>();

    private final LinkedHashMap<BlockPos, VirtualMovingBlock> movingBlocks = new LinkedHashMap<>();

    private final int minY;
    private final int maxY;

    public VirtualWorld(Level level, BlockPos center) {
        this.minY = level.dimensionType().minY();
        this.maxY = level.dimensionType().height() + this.minY;

        BlockPos.betweenClosedStream(
                center.offset(-CAPTURE_RADIUS, -CAPTURE_RADIUS, -CAPTURE_RADIUS),
                center.offset(CAPTURE_RADIUS, CAPTURE_RADIUS, CAPTURE_RADIUS)
        ).forEach(pos -> {
            BlockState state = level.getBlockState(pos);
            blocks.put(pos.immutable(), state);
        });
    }

    public BlockState getBlockState(BlockPos pos) {
        BlockState state = blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());

        // Treat piston heads as air for simulation testing purposes
        if (state.is(Blocks.PISTON_HEAD)) {
            return Blocks.AIR.defaultBlockState();
        }

        return state;
    }

    public void setBlock(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
    }

    public void removeBlock(BlockPos pos) {
        blocks.put(pos.immutable(), Blocks.AIR.defaultBlockState());
    }

    public static class VirtualMovingBlock {
        public final BlockState state;
        public int timer = 2;
        public VirtualMovingBlock(BlockState state) { this.state = state; }
    }

    public void addMovingBlock(BlockPos pos, BlockState state) {
        movingBlocks.put(pos.immutable(), new VirtualMovingBlock(state));
    }

    public boolean hasActiveMovingBlocks() {
        return !movingBlocks.isEmpty();
    }

    public List<BlockPos> tickMovingBlockTimers() {
        List<BlockPos> ready = new ArrayList<>();
        for (Map.Entry<BlockPos, VirtualMovingBlock> entry : movingBlocks.entrySet()) {
            entry.getValue().timer--;
            if (entry.getValue().timer <= 0) {
                ready.add(entry.getKey());
            }
        }
        return ready;
    }

    /**
     * Solidifies a single moving block, taking it out of stasis and placing it in the world.
     */
    public void solidifyMovingBlock(BlockPos pos) {
        VirtualMovingBlock mb = movingBlocks.remove(pos);
        if (mb != null) {
            setBlock(pos, mb.state);
        }
    }

    public boolean isPushable(BlockPos pos, Direction pushDir, boolean canDestroy, Direction pistonFacing) {
        if (pos.getY() < minY || pos.getY() > maxY - 1) return false;

        if (movingBlocks.containsKey(pos)) return false;

        BlockState state = getBlockState(pos);

        if (state.isAir()) return true;
        if (isImmovable(state)) return false;
        if (pushDir == Direction.DOWN && pos.getY() == minY) return false;
        if (pushDir == Direction.UP && pos.getY() == maxY - 1) return false;

        if (isPistonBlock(state)) {
            return !state.getValue(PistonBaseBlock.EXTENDED);
        }

        if (state.getDestroySpeed(null, pos) == -1.0F) return false;

        PushReaction reaction = state.getPistonPushReaction();
        if (reaction == PushReaction.BLOCK) return false;
        if (reaction == PushReaction.DESTROY) return canDestroy;
        if (reaction == PushReaction.PUSH_ONLY) return pushDir == pistonFacing;

        return !state.hasBlockEntity();
    }

    private boolean isImmovable(BlockState state) {
        return state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)
                || state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.REINFORCED_DEEPSLATE)
                || state.is(Blocks.BEDROCK);
    }

    private boolean isPistonBlock(BlockState state) {
        return state.is(Blocks.PISTON) || state.is(Blocks.STICKY_PISTON);
    }
}