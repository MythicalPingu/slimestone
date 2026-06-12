package com.pingu.slimestone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

public class SimPistonMovingEntity {
    BlockPos pos;
    BlockState movedState;
    Direction direction;
    boolean extending;
    boolean isSourcePiston;
    float progress = 0.0f;

    public SimPistonMovingEntity(BlockPos pos, BlockState movedState, Direction direction, boolean extending, boolean isSource) {
        this.pos = pos;
        this.movedState = movedState;
        this.direction = direction;
        this.extending = extending;
        this.isSourcePiston = isSource;
    }

    public void tick(VirtualLevel level) {
        progress += 0.5f;
        //level.log("Moving block at " + pos.toShortString() + " progressing: " + progress);

        if (progress >= 1.0f) {
            level.blockEntities.remove(pos);
            level.setBlock(pos, movedState);

            if (movedState.getBlock() instanceof PistonBaseBlock) {
                level.checkIfExtend(pos, movedState);
            }
            level.updateNeighborsAt(pos);
        }
    }

    public void finalTick(VirtualLevel level) {
        level.blockEntities.remove(pos);

        BlockState stateToPlace = isSourcePiston ? Blocks.AIR.defaultBlockState() : movedState;
        level.setBlock(pos, stateToPlace);

        if (stateToPlace.getBlock() instanceof PistonBaseBlock) {
            level.checkIfExtend(pos, stateToPlace);
        }
        level.updateNeighborsAt(pos);
    }
}