package com.pingu.simulator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;

import java.util.ArrayList;
import java.util.List;

/**
 * 1:1 Port of Vanilla PistonStructureResolver, adapted to read from VirtualWorld.
 * Handles Slime/Honey adhesion, branching blocks, and 12-block push limits.
 */
public class VirtualStructureResolver {

    private static final int MAX_PUSH_DEPTH = 12;

    private final VirtualWorld world;
    private final BlockPos pistonPos;
    private final boolean extending;
    private final BlockPos startPos;
    private final Direction pushDirection;
    private final List<BlockPos> toPush = new ArrayList<>();
    private final List<BlockPos> toDestroy = new ArrayList<>();
    private final Direction pistonDirection;

    public VirtualStructureResolver(VirtualWorld world, BlockPos pistonPos, Direction pistonDirection, boolean extending) {
        this.world = world;
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
        this.toPush.clear();
        this.toDestroy.clear();

        BlockState startState = this.world.getBlockState(this.startPos);

        if (!this.world.isPushable(this.startPos, this.pushDirection, false, this.pistonDirection)) {
            if (this.extending && startState.getPistonPushReaction() == PushReaction.DESTROY) {
                this.toDestroy.add(this.startPos);
                return true;
            } else {
                return false;
            }
        } else if (!this.addBlockLine(this.startPos, this.pushDirection)) {
            return false;
        } else {
            for (int i = 0; i < this.toPush.size(); i++) {
                BlockPos pushPos = this.toPush.get(i);
                if (isSticky(this.world.getBlockState(pushPos)) && !this.addBranchingBlocks(pushPos)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static boolean isSticky(BlockState state) {
        return state.is(Blocks.SLIME_BLOCK) || state.is(Blocks.HONEY_BLOCK);
    }

    private static boolean canStickToEachOther(BlockState stateA, BlockState stateB) {
        if (stateA.is(Blocks.HONEY_BLOCK) && stateB.is(Blocks.SLIME_BLOCK)) {
            return false;
        } else if (stateA.is(Blocks.SLIME_BLOCK) && stateB.is(Blocks.HONEY_BLOCK)) {
            return false;
        } else {
            return isSticky(stateA) || isSticky(stateB);
        }
    }

    private boolean addBlockLine(BlockPos pos, Direction dir) {
        BlockState state = this.world.getBlockState(pos);

        // This explicitly prevents Air from ever being added to the push list
        if (state.isAir()) {
            return true;
        } else if (!this.world.isPushable(pos, this.pushDirection, false, dir)) {
            return true;
        } else if (pos.equals(this.pistonPos)) {
            return true;
        } else if (this.toPush.contains(pos)) {
            return true;
        } else {
            int lineLen = 1;
            if (lineLen + this.toPush.size() > MAX_PUSH_DEPTH) {
                return false;
            } else {
                // 1. Traverse backwards to find all blocks stuck behind this one
                while (isSticky(state)) {
                    BlockPos prevPos = pos.relative(this.pushDirection.getOpposite(), lineLen);
                    BlockState prevState = state;
                    state = this.world.getBlockState(prevPos);

                    if (state.isAir() || !canStickToEachOther(prevState, state) ||
                            !this.world.isPushable(prevPos, this.pushDirection, false, this.pushDirection.getOpposite()) ||
                            prevPos.equals(this.pistonPos)) {
                        break;
                    }

                    if (++lineLen + this.toPush.size() > MAX_PUSH_DEPTH) {
                        return false;
                    }
                }

                int blocksAdded = 0;

                // 2. Add blocks to the list from furthest behind up to the current block
                for (int i = lineLen - 1; i >= 0; i--) {
                    this.toPush.add(pos.relative(this.pushDirection.getOpposite(), i));
                    blocksAdded++;
                }

                int distanceInFront = 1;

                // 3. Traverse forwards to push all blocks in front
                while (true) {
                    BlockPos nextPos = pos.relative(this.pushDirection, distanceInFront);
                    int existingIndex = this.toPush.indexOf(nextPos);

                    if (existingIndex > -1) {
                        this.reorderListAtCollision(blocksAdded, existingIndex);

                        for (int i = 0; i <= existingIndex + blocksAdded; i++) {
                            BlockPos checkPos = this.toPush.get(i);
                            if (isSticky(this.world.getBlockState(checkPos)) && !this.addBranchingBlocks(checkPos)) {
                                return false;
                            }
                        }
                        return true;
                    }

                    state = this.world.getBlockState(nextPos);
                    if (state.isAir()) {
                        return true;
                    }

                    if (!this.world.isPushable(nextPos, this.pushDirection, true, this.pushDirection) || nextPos.equals(this.pistonPos)) {
                        return false;
                    }

                    if (state.getPistonPushReaction() == PushReaction.DESTROY) {
                        this.toDestroy.add(nextPos);
                        return true;
                    }

                    if (this.toPush.size() >= MAX_PUSH_DEPTH) {
                        return false;
                    }

                    this.toPush.add(nextPos);
                    blocksAdded++;
                    distanceInFront++;
                }
            }
        }
    }

    private void reorderListAtCollision(int addedCount, int collisionIndex) {
        List<BlockPos> list1 = new ArrayList<>(this.toPush.subList(0, collisionIndex));
        List<BlockPos> list2 = new ArrayList<>(this.toPush.subList(this.toPush.size() - addedCount, this.toPush.size()));
        List<BlockPos> list3 = new ArrayList<>(this.toPush.subList(collisionIndex, this.toPush.size() - addedCount));

        this.toPush.clear();
        this.toPush.addAll(list1);
        this.toPush.addAll(list2);
        this.toPush.addAll(list3);
    }

    private boolean addBranchingBlocks(BlockPos pos) {
        BlockState state = this.world.getBlockState(pos);

        for (Direction dir : Direction.values()) {
            if (dir.getAxis() != this.pushDirection.getAxis()) {
                BlockPos neighborPos = pos.relative(dir);
                BlockState neighborState = this.world.getBlockState(neighborPos);

                if (canStickToEachOther(neighborState, state) && !this.addBlockLine(neighborPos, dir)) {
                    return false;
                }
            }
        }
        return true;
    }

    public Direction getPushDirection() { return this.pushDirection; }
    public List<BlockPos> getToPush() { return this.toPush; }
    public List<BlockPos> getToDestroy() { return this.toDestroy; }
}