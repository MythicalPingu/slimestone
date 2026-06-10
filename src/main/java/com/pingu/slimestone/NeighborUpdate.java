package com.pingu.slimestone;

import net.minecraft.core.BlockPos;

public record NeighborUpdate(BlockPos pos, BlockPos fromPos) {}