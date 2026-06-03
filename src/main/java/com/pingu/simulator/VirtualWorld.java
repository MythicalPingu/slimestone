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

    // NEW: LinkedHashMap preserves the exact order moving blocks are created (Arrival Order)
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
        return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
    }

    public void setBlock(BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
    }

    public void removeBlock(BlockPos pos) {
        blocks.put(pos.immutable(), Blocks.AIR.defaultBlockState());
    }

    // --- NEW: Moving Block Phase Logic ---

    public static class VirtualMovingBlock {
        public final BlockState state;
        public int timer = 2; // Ticks down to 0
        public VirtualMovingBlock(BlockState state) { this.state = state; }
    }

    public void addMovingBlock(BlockPos pos, BlockState state) {
        movingBlocks.put(pos.immutable(), new VirtualMovingBlock(state));
    }

    public boolean hasActiveMovingBlocks() {
        return !movingBlocks.isEmpty();
    }

    /**
     * Decrements moving block timers. If a block hits 0, it solidifies.
     * @return A list of positions that solidified this tick, strictly in arrival order.
     */
    public List<BlockPos> tickMovingBlocks() {
        List<BlockPos> solidified = new ArrayList<>();
        Iterator<Map.Entry<BlockPos, VirtualMovingBlock>> iterator = movingBlocks.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<BlockPos, VirtualMovingBlock> entry = iterator.next();
            VirtualMovingBlock mb = entry.getValue();

            mb.timer--;

            if (mb.timer <= 0) {
                // Solidify into the world
                setBlock(entry.getKey(), mb.state);
                solidified.add(entry.getKey());
                iterator.remove();
            }
        }
        return solidified;
    }

    // -------------------------------------

    public boolean isPushable(BlockPos pos, Direction pushDir, boolean canDestroy, Direction pistonFacing) {
        if (pos.getY() < minY || pos.getY() > maxY - 1) return false;

        // NEW: Moving blocks act as immovable at their destination
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