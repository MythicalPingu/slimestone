package com.pingu.simulator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public class PistonEvent {

    public enum Result {
        SUCCESS_PULLED, SUCCESS_NO_PULL, FAILED_BLOCKED
    }

    private final int gameTick;
    private final BlockPos pistonPos;
    private final Direction facing;
    private final Result result;

    private final BlockState pulledBlock;
    private final BlockPos pulledFrom;
    private final List<String> changes;

    public PistonEvent(
            int gameTick, BlockPos pistonPos, Direction facing, Result result,
            BlockState pulledBlock, BlockPos pulledFrom, List<String> changes
    ) {
        this.gameTick = gameTick;
        this.pistonPos = pistonPos;
        this.facing = facing;
        this.result = result;
        this.pulledBlock = pulledBlock;
        this.pulledFrom = pulledFrom;
        this.changes = changes;
    }

    public int getGameTick() { return gameTick; }
    public BlockPos getPistonPos() { return pistonPos; }
    public Direction getFacing() { return facing; }
    public Result getResult() { return result; }
    public BlockState getPulledBlock() { return pulledBlock; }
    public BlockPos getPulledFrom() { return pulledFrom; }
    public List<String> getChanges() { return changes; }

    public boolean isSuccess() {
        return result == Result.SUCCESS_PULLED || result == Result.SUCCESS_NO_PULL;
    }
}