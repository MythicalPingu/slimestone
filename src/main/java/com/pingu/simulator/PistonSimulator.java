package com.pingu.simulator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class PistonSimulator {

    private final VirtualWorld world;
    private final List<PistonEvent> timeline = new ArrayList<>();

    private enum Action { PUSH, PULL }
    private record WorkItem(BlockPos pos, Direction facing, Action action) {}

    private final Map<Integer, List<WorkItem>> scheduledPistons = new TreeMap<>();
    private static final int MAX_TICK = 2000;

    public PistonSimulator(VirtualWorld world) {
        this.world = world;
    }

    public List<PistonEvent> simulate(BlockPos startPos, Direction facing) {
        timeline.clear();
        scheduledPistons.clear();

        BlockState pistonState = world.getBlockState(startPos);
        if (!pistonState.hasProperty(PistonBaseBlock.EXTENDED)) return timeline;

        boolean isExtended = pistonState.getValue(PistonBaseBlock.EXTENDED);

        // Intelligently start with Push or Pull based on the targeted piston
        if (isExtended) {
            scheduledPistons.computeIfAbsent(0, k -> new ArrayList<>()).add(new WorkItem(startPos, facing, Action.PULL));
        } else {
            scheduledPistons.computeIfAbsent(0, k -> new ArrayList<>()).add(new WorkItem(startPos, facing, Action.PUSH));
        }

        int tick = 0;

        while (tick <= MAX_TICK && (!scheduledPistons.isEmpty() || world.hasActiveMovingBlocks())) {

            // === PHASE 1: PISTON EVENT PHASE ===
            // Using a Queue ensures updates triggered *during* this tick are processed immediately before Phase 2.
            List<WorkItem> phase1Work = scheduledPistons.remove(tick);
            if (phase1Work != null) {
                Queue<WorkItem> currentTickQueue = new LinkedList<>(phase1Work);

                while (!currentTickQueue.isEmpty()) {
                    WorkItem work = currentTickQueue.poll();

                    if (work.action() == Action.PULL) {
                        PistonEvent event = simulateRetraction(tick, work.pos(), work.facing(), currentTickQueue);
                        if (event != null) timeline.add(event);
                    } else if (work.action() == Action.PUSH) {
                        PistonEvent event = simulateExtension(tick, work.pos(), work.facing(), currentTickQueue);
                        if (event != null) timeline.add(event);
                    }
                }
            }

            // === PHASE 2: MOVING BLOCK PHASE ===
            List<BlockPos> readyBlocks = world.tickMovingBlockTimers();

            for (BlockPos pos : readyBlocks) {

                world.solidifyMovingBlock(pos);
                BlockState bs = world.getBlockState(pos);

                if ((bs.is(Blocks.PISTON) || bs.is(Blocks.STICKY_PISTON)) && !bs.getValue(PistonBaseBlock.EXTENDED)) {
                    Direction childFacing = bs.getValue(PistonBaseBlock.FACING);
                    if (isPowered(pos, childFacing)) {
                        VirtualStructureResolver resolver = new VirtualStructureResolver(world, pos, childFacing, true);
                        if (resolver.resolve()) {
                            scheduledPistons.computeIfAbsent(tick + 1, k -> new ArrayList<>())
                                    .add(new WorkItem(pos, childFacing, Action.PUSH));
                        }
                    }
                }

                if (bs.is(Blocks.REDSTONE_BLOCK)) {
                    handleRedstoneBlockArrival(pos, tick);
                }
            }

            tick++;
        }
        return timeline;
    }

    private PistonEvent simulateRetraction(int tick, BlockPos basePos, Direction facing, Queue<WorkItem> currentTickQueue) {
        BlockState pistonState = world.getBlockState(basePos);

        if (!pistonState.is(Blocks.PISTON) && !pistonState.is(Blocks.STICKY_PISTON)) return null;

        // Prevent double retractions from stacked events
        if (!pistonState.getValue(PistonBaseBlock.EXTENDED)) return null;

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

                world.addMovingBlock(dest, bs);

                if (bs.is(Blocks.REDSTONE_BLOCK)) {
                    handleRedstoneBlockRemoval(bp, currentTickQueue);
                }
            }

            world.setBlock(basePos, pistonState.setValue(PistonBaseBlock.EXTENDED, false));
            return new PistonEvent(tick, basePos, facing, PistonEvent.Result.SUCCESS_PULLED, pulledBlock, pulledFrom, changes);

        } else {
            world.setBlock(basePos, pistonState.setValue(PistonBaseBlock.EXTENDED, false));
            return new PistonEvent(tick, basePos, facing, PistonEvent.Result.SUCCESS_NO_PULL, null, null, changes);
        }
    }

    private PistonEvent simulateExtension(int tick, BlockPos basePos, Direction facing, Queue<WorkItem> currentTickQueue) {
        BlockState pistonState = world.getBlockState(basePos);

        if (!pistonState.is(Blocks.PISTON) && !pistonState.is(Blocks.STICKY_PISTON)) return null;

        // Prevent double extensions from stacked events
        if (pistonState.getValue(PistonBaseBlock.EXTENDED)) return null;

        VirtualStructureResolver resolver = new VirtualStructureResolver(world, basePos, facing, true);
        boolean canExtend = resolver.resolve();

        if (!canExtend) return makeFailedEvent(tick, basePos, facing);

        List<BlockPos> toPush = resolver.getToPush();
        Collections.reverse(toPush);

        List<String> changes = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        for (BlockPos bp : toPush) states.add(world.getBlockState(bp));

        for (BlockPos bp : toPush) world.removeBlock(bp);

        Direction pushDir = facing;
        for (int i = 0; i < toPush.size(); i++) {
            BlockPos bp = toPush.get(i);
            BlockState bs = states.get(i);
            BlockPos dest = bp.relative(pushDir);

            String blockName = bs.getBlock().getName().getString();
            changes.add(String.format("%s %s -> %s", blockName, formatPos(bp), formatPos(dest)));

            world.addMovingBlock(dest, bs);

            if (bs.is(Blocks.REDSTONE_BLOCK)) {
                handleRedstoneBlockRemoval(bp, currentTickQueue);
            }
        }

        world.setBlock(basePos, pistonState.setValue(PistonBaseBlock.EXTENDED, true));
        return new PistonEvent(tick, basePos, facing, PistonEvent.Result.SUCCESS_PUSHED, null, null, changes);
    }

    private void handleRedstoneBlockRemoval(BlockPos pos, Queue<WorkItem> currentTickQueue) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = world.getBlockState(neighborPos);

            if (neighborState.is(Blocks.STICKY_PISTON)) {
                // Must be EXTENDED to retract
                if (neighborState.getValue(PistonBaseBlock.EXTENDED)) {
                    Direction neighborFacing = neighborState.getValue(PistonBaseBlock.FACING);
                    // Check power state (ignoring block directly in front)
                    if (!isPowered(neighborPos, neighborFacing)) {
                        // Queue into the active block event phase
                        currentTickQueue.add(new WorkItem(neighborPos, neighborFacing, Action.PULL));
                    }
                }
            }
        }
    }

    private void handleRedstoneBlockArrival(BlockPos pos, int tick) {
        for (Direction dir : Direction.values()) {
            BlockPos neighborPos = pos.relative(dir);
            BlockState neighborState = world.getBlockState(neighborPos);

            if ((neighborState.is(Blocks.PISTON) || neighborState.is(Blocks.STICKY_PISTON)) && !neighborState.getValue(PistonBaseBlock.EXTENDED)) {
                Direction facing = neighborState.getValue(PistonBaseBlock.FACING);
                if (isPowered(neighborPos, facing)) {
                    VirtualStructureResolver resolver = new VirtualStructureResolver(world, neighborPos, facing, true);
                    if (resolver.resolve()) {
                        scheduledPistons.computeIfAbsent(tick + 1, k -> new ArrayList<>())
                                .add(new WorkItem(neighborPos, facing, Action.PUSH));
                    }
                }
            }
        }
    }



    private boolean isPowered(BlockPos pos, Direction pistonFacing) {
        for (Direction dir : Direction.values()) {
            // "Redstone blocks infront of the facing direction doesnt count as being powered"
            if (dir == pistonFacing) continue;

            if (world.getBlockState(pos.relative(dir)).is(Blocks.REDSTONE_BLOCK)) {
                return true;
            }
        }
        return false;
    }

    private PistonEvent makeFailedEvent(int tick, BlockPos pos, Direction facing) {
        return new PistonEvent(tick, pos, facing, PistonEvent.Result.FAILED_BLOCKED, null, null, List.of());
    }

    private String formatPos(BlockPos pos) {
        return "[" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }
}