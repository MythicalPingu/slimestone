package com.pingu.slimestone;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * A pending scheduled block-tick entry for the Virtual Level.
 *
 * Ordering mirrors Minecraft's {@code ScheduledTick.DRAIN_ORDER}:
 *   1. Earlier {@code triggerTick} fires first.
 *   2. On ties, lower {@code subTickOrder} fires first (FIFO insertion order).
 *
 * {@code pos} is always stored immutable so it is safe to use as a map/queue key.
 */
public record SimScheduledTick(BlockPos pos, Block type, long triggerTick, long subTickOrder)
        implements Comparable<SimScheduledTick> {

    /** Canonical constructor — guarantees an immutable BlockPos. */
    public SimScheduledTick(BlockPos pos, Block type, long triggerTick, long subTickOrder) {
        this.pos          = pos.immutable();
        this.type         = type;
        this.triggerTick  = triggerTick;
        this.subTickOrder = subTickOrder;
    }

    @Override
    public int compareTo(SimScheduledTick other) {
        int c = Long.compare(this.triggerTick, other.triggerTick);
        if (c != 0) return c;
        return Long.compare(this.subTickOrder, other.subTickOrder);
    }
}