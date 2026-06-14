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
    float progress = -0.5f;

    public SimPistonMovingEntity(BlockPos pos, BlockState movedState, Direction direction, boolean extending, boolean isSource) {
        this.pos = pos;
        this.movedState = movedState;
        this.direction = direction;
        this.extending = extending;
        this.isSourcePiston = isSource;
    }

    public void tick(VirtualLevel level) {
        progress += 0.5f;

        if (progress >= 1.0f) {
            level.blockEntities.remove(pos);

            // Vanilla uses flag 67 for arriving blocks: UPDATE_ALL (3) | UPDATE_MOVED_BY_PISTON (64)
            // Notice we do NOT pass UPDATE_KNOWN_SHAPE (16) so shape updates trigger natively.
            level.setBlock(pos, movedState, VirtualLevel.UPDATE_ALL | VirtualLevel.UPDATE_MOVED_BY_PISTON);

            if (movedState.getBlock() instanceof PistonBaseBlock) {
                level.checkIfExtend(pos, movedState);
            }
            // Removed explicit level.updateNeighborsAt(pos) - setBlock handles it now
        }
    }

    public void finalTick(VirtualLevel level) {
        level.blockEntities.remove(pos);

        BlockState stateToPlace = isSourcePiston ? Blocks.AIR.defaultBlockState() : movedState;

        // Vanilla uses flag 3 for finalTick block setting
        level.setBlock(pos, stateToPlace, VirtualLevel.UPDATE_ALL | VirtualLevel.UPDATE_MOVED_BY_PISTON);

        if (stateToPlace.getBlock() instanceof PistonBaseBlock) {
            level.checkIfExtend(pos, stateToPlace);
        }
        // Removed explicit level.updateNeighborsAt(pos) - setBlock handles it now
    }
}