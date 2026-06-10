package com.pingu.slimestone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

public record BlockEvent(BlockPos pos, BlockState state, int type, Direction dir) {}