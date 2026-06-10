package com.pingu.slimestone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.List;

public class SimPistonResolver {
    VirtualLevel level;
    BlockPos pistonPos;
    BlockPos startPos;
    Direction pushDirection;
    Direction pistonDirection;
    boolean extending;
    private BlockPos failurePos = null;
    private String failureReason = "Unknown";

    List<BlockPos> toPush = new ArrayList<>();
    List<BlockPos> toDestroy = new ArrayList<>();

    public SimPistonResolver(VirtualLevel level, BlockPos pistonPos, Direction pistonDirection, boolean extending) {
        this.level = level;
        this.pistonPos = pistonPos;
        this.pistonDirection = pistonDirection;
        this.extending = extending;

        if (extending) {
            this.pushDirection = pistonDirection;
            this.startPos = pistonPos.relative(pistonDirection);
        } else {
            this.pushDirection = pistonDirection.getOpposite();
            this.startPos = pistonPos.relative(pistonDirection, 2);
        }
    }

    public boolean resolve() {
        toPush.clear();
        toDestroy.clear();
        BlockState startState = level.getBlockState(startPos);

        if (startState.isAir()) return true;

        if (!isPushable(startState, pushDirection, startPos)) {
            if (extending && startState.getPistonPushReaction() == PushReaction.DESTROY) {
                toDestroy.add(startPos);
                return true;
            }
            this.failurePos = startPos;
            this.failureReason = "Unpushable block at " + startPos.toShortString();
            return false;
        }

        if (!addBlockLine(startPos, pushDirection)) return false;

        for (int i = 0; i < toPush.size(); i++) {
            BlockPos pos = toPush.get(i);
            if (isSticky(level.getBlockState(pos)) && !addBranchingBlocks(pos)) {
                return false;
            }
        }
        return true;
    }

    private boolean addBlockLine(BlockPos pos, Direction dir) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || !isPushable(state, dir, pos) || pos.equals(pistonPos) || toPush.contains(pos)) return true;

        int count = 1;
        while (isSticky(state)) {
            BlockPos next = pos.relative(pushDirection.getOpposite(), count);
            BlockState nextState = level.getBlockState(next);
            if (nextState.isAir() || !canStickToEachOther(state, nextState) || next.equals(pistonPos)) break;
            state = nextState;
            count++;
            if (count + toPush.size() > 12) {
                this.failurePos = pos;
                this.failureReason = "Piston push limit (12) reached backwards";
                return false;
            }
        }

        int addedCount = 0;
        for (int i = count - 1; i >= 0; i--) {
            toPush.add(pos.relative(pushDirection.getOpposite(), i));
            addedCount++;
        }

        int step = 1;
        while (true) {
            BlockPos nextPos = pos.relative(pushDirection, step);
            int idx = toPush.indexOf(nextPos);
            if (idx > -1) {
                reorderListAtCollision(addedCount, idx);
                for (int i = 0; i <= idx + addedCount; i++) {
                    BlockPos p = toPush.get(i);
                    if (isSticky(level.getBlockState(p)) && !addBranchingBlocks(p)) return false;
                }
                return true;
            }

            BlockState nextState = level.getBlockState(nextPos);
            if (nextState.isAir()) return true;

            if (!isPushable(nextState, pushDirection, nextPos) || nextPos.equals(pistonPos)) {
                this.failurePos = nextPos;
                this.failureReason = "Blocked by unpushable block at " + nextPos.toShortString();
                return false;
            }

            if (nextState.getPistonPushReaction() == PushReaction.DESTROY) {
                toDestroy.add(nextPos);
                return true;
            }

            if (toPush.size() >= 12) {
                this.failurePos = nextPos;
                this.failureReason = "Piston push limit (12) reached forwards";
                return false;
            }
            toPush.add(nextPos);
            addedCount++;
            step++;
        }
    }

    private boolean addBranchingBlocks(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        for (Direction dir : Direction.values()) {
            if (dir.getAxis() != pushDirection.getAxis()) {
                BlockPos adj = pos.relative(dir);
                BlockState adjState = level.getBlockState(adj);
                if (canStickToEachOther(adjState, state) && !addBlockLine(adj, dir)) return false;
            }
        }
        return true;
    }

    public BlockPos getFailurePos() { return failurePos; }
    public String getFailureReason() { return failureReason; }

    private void reorderListAtCollision(int newlyAddedCount, int collisionIndex) {
        List<BlockPos> list1 = new ArrayList<>(toPush.subList(0, collisionIndex));
        List<BlockPos> list2 = new ArrayList<>(toPush.subList(toPush.size() - newlyAddedCount, toPush.size()));
        List<BlockPos> list3 = new ArrayList<>(toPush.subList(collisionIndex, toPush.size() - newlyAddedCount));
        toPush.clear();
        toPush.addAll(list1);
        toPush.addAll(list2);
        toPush.addAll(list3);
    }

    private boolean isPushable(BlockState state, Direction dir, BlockPos pos) {
        if (level.blockEntities.containsKey(pos)) return false;
        if (state.isAir() || state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN) || state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.REINFORCED_DEEPSLATE) || state.is(Blocks.BEDROCK)) return false;

        if (!state.is(Blocks.PISTON) && !state.is(Blocks.STICKY_PISTON)) {
            if (state.getPistonPushReaction() == PushReaction.BLOCK) return false;
        } else {
            if (state.getValue(PistonBaseBlock.EXTENDED)) return false;
        }
        return true;
    }

    private boolean isSticky(BlockState state) {
        return state.is(Blocks.SLIME_BLOCK) || state.is(Blocks.HONEY_BLOCK);
    }

    private boolean canStickToEachOther(BlockState state1, BlockState state2) {
        if (state1.is(Blocks.HONEY_BLOCK) && state2.is(Blocks.SLIME_BLOCK)) return false;
        if (state1.is(Blocks.SLIME_BLOCK) && state2.is(Blocks.HONEY_BLOCK)) return false;
        return isSticky(state1) || isSticky(state2);
    }
}