package com.pingu.simulator;

import com.pingu.Slimestone;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class PistonSimulator {

    private final VirtualWorld world;
    private final List<PistonEvent> timeline = new ArrayList<>();

    // TreeMap ensures we always process ticks in chronological order
    private final Map<Integer, List<WorkItem>> scheduledPistons = new TreeMap<>();

    private static final int MAX_TICK = 2000;

    public PistonSimulator(VirtualWorld world) {
        this.world = world;
    }

    public List<PistonEvent> simulate(BlockPos startPos, Direction facing) {
        timeline.clear();
        scheduledPistons.clear();

        BlockPos basePos = startPos.relative(facing.getOpposite());
        BlockState pistonState = world.getBlockState(startPos);

        if (pistonState.is(Blocks.PISTON) || pistonState.is(Blocks.STICKY_PISTON)) {
            world.removeBlock(startPos);
            world.setBlock(basePos, pistonState);
        }

        scheduledPistons.computeIfAbsent(0, k -> new ArrayList<>()).add(new WorkItem(basePos, facing));

        int tick = 0;

        // Loop continues as long as there are pending pistons OR blocks still moving in stasis
        while (tick <= MAX_TICK && (!scheduledPistons.isEmpty() || world.hasActiveMovingBlocks())) {

            // === PHASE 1: PISTON PHASE ===
            List<WorkItem> phase1Work = scheduledPistons.remove(tick);
            if (phase1Work != null) {
                for (WorkItem work : phase1Work) {
                    PistonEvent event = simulateRetraction(tick, work.pos(), work.facing());
                    timeline.add(event);
                }
            }

            // === PHASE 2: MOVING BLOCK PHASE ===
            List<BlockPos> solidifiedThisTick = world.tickMovingBlocks();

            // Any sticky piston that arrived/solidified at the end of this tick gets to fire at the start of next tick
            for (BlockPos pos : solidifiedThisTick) {
                BlockState bs = world.getBlockState(pos);
                if (bs.is(Blocks.STICKY_PISTON) && !bs.getValue(PistonBaseBlock.EXTENDED)) {
                    Direction childFacing = bs.getValue(PistonBaseBlock.FACING);
                    scheduledPistons.computeIfAbsent(tick + 1, k -> new ArrayList<>()).add(new WorkItem(pos, childFacing));
                }
            }

            tick++;
        }
        return timeline;
    }

    private PistonEvent simulateRetraction(int tick, BlockPos basePos, Direction facing) {
        BlockState pistonState = world.getBlockState(basePos);

        if (!pistonState.is(Blocks.PISTON) && !pistonState.is(Blocks.STICKY_PISTON)) {
            return makeFailedEvent(tick, basePos, facing);
        }

        boolean isSticky = pistonState.is(Blocks.STICKY_PISTON);
        VirtualStructureResolver resolver = new VirtualStructureResolver(world, basePos, facing, false);
        boolean canRetract = resolver.resolve();

        if (!canRetract) return makeFailedEvent(tick, basePos, facing);

        List<BlockPos> toPull = resolver.getToPush();
        Collections.reverse(toPull);

        List<String> changes = new ArrayList<>();
        BlockState pulledBlock = null;
        BlockPos pulledFrom = null;

        if (isSticky && !toPull.isEmpty()) {
            Direction pullDir = facing.getOpposite();

            List<BlockState> states = new ArrayList<>();
            for (BlockPos bp : toPull) states.add(world.getBlockState(bp));

            for (BlockPos bp : toPull) world.removeBlock(bp);

            for (int i = 0; i < toPull.size(); i++) {
                BlockPos bp = toPull.get(i);
                BlockState bs = states.get(i);
                BlockPos dest = bp.relative(pullDir);

                if (bp.equals(basePos.relative(facing, 2))) {
                    pulledBlock = bs;
                    pulledFrom = bp;
                }

                String blockName = bs.getBlock().getName().getString();
                changes.add(String.format("%s %s -> %s", blockName, formatPos(bp), formatPos(dest)));

                // NEW: Send it to the Moving Block stasis instead of placing it immediately
                world.addMovingBlock(dest, bs);
            }

            return new PistonEvent(tick, basePos, facing, PistonEvent.Result.SUCCESS_PULLED, pulledBlock, pulledFrom, changes);

        } else {
            return new PistonEvent(tick, basePos, facing, PistonEvent.Result.SUCCESS_NO_PULL, null, null, changes);
        }
    }

    private PistonEvent makeFailedEvent(int tick, BlockPos pos, Direction facing) {
        return new PistonEvent(tick, pos, facing, PistonEvent.Result.FAILED_BLOCKED, null, null, List.of());
    }

    private String formatPos(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    private record WorkItem(BlockPos pos, Direction facing) {}
}