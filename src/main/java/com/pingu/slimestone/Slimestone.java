package com.pingu.slimestone;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.PistonType;
import net.minecraft.world.level.material.PushReaction;

import java.util.*;

public class Slimestone implements ModInitializer {

    // Vanilla NeighborUpdater.UPDATE_ORDER
    private static final Direction[] UPDATE_ORDER = new Direction[]{Direction.WEST, Direction.EAST, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH};

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("slimestone")
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                BlockPos target = BlockPosArgument.getLoadedBlockPos(context, "pos");

                                runSimulation(player, target);
                                return 1;
                            })));
        });
    }

    private void runSimulation(ServerPlayer player, BlockPos targetPos) {
        player.sendSystemMessage(Component.literal("§a[Slimestone] Starting Virtual Simulation at " + targetPos.toShortString()));
        VirtualLevel level = new VirtualLevel(player);

        for (int x = -8; x <= 8; x++) {
            for (int y = -8; y <= 8; y++) {
                for (int z = -8; z <= 8; z++) {
                    BlockPos p = targetPos.offset(x, y, z);
                    level.setBlockRaw(p, player.level().getBlockState(p));
                }
            }
        }

        BlockState targetState = level.getBlockState(targetPos);
        if (!(targetState.getBlock() instanceof PistonBaseBlock)) {
            player.sendSystemMessage(Component.literal("§cTarget is not a piston!"));
            return;
        }

        level.log("Simulation Initialized. Checking initial power for piston at " + targetPos.toShortString());
        level.checkIfExtend(targetPos, targetState);
        level.runTickLoop(100);
    }

    // ==========================================
    // VIRTUAL LEVEL & TICK ENGINE
    // ==========================================
    static class VirtualLevel {
        private final ServerPlayer player;
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();
        private final Map<BlockPos, SimPistonMovingEntity> blockEntities = new LinkedHashMap<>();

        private final Queue<BlockEvent> blockEvents = new ArrayDeque<>();
        private final Queue<NeighborUpdate> neighborUpdates = new ArrayDeque<>();

        private int currentTick = 0;
        private String currentPhase = "INIT";

        public VirtualLevel(ServerPlayer player) {
            this.player = player;
        }

        public void log(String msg) {
            player.sendSystemMessage(Component.literal(String.format("§7[GT %d | %s] §f%s", currentTick, currentPhase, msg)));
        }

        public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }

        public void setBlockRaw(BlockPos pos, BlockState state) {
            blocks.put(pos.immutable(), state);
        }

        public void setBlock(BlockPos pos, BlockState state) {
            setBlockRaw(pos, state);
            log("Set block at " + pos.toShortString() + " to " + state.getBlock().getName().getString());
        }

        public void runTickLoop(int maxTicks) {
            for (currentTick = 0; currentTick < maxTicks; currentTick++) {
                boolean active = false;

                // Phase 1: Block Entities (In vanilla, block entities tick BEFORE block events process)
                currentPhase = "BLOCK ENTITIES";
                List<SimPistonMovingEntity> tickingBEs = new ArrayList<>(blockEntities.values());
                for (SimPistonMovingEntity be : tickingBEs) {
                    active = true;
                    be.tick(this);
                    flushNeighborUpdates();
                }

                // Phase 2: Block Events (Pistons decide to start moving)
                currentPhase = "BLOCK EVENTS";
                while (!blockEvents.isEmpty()) {
                    active = true;

                    // 1. PEEK: Read the event but keep it in the queue so the deduplicator sees it
                    BlockEvent event = blockEvents.peek();

                    // 2. Execute the event and all resulting neighbor updates
                    triggerPistonEvent(event.pos, event.state, event.type, event.dir);
                    flushNeighborUpdates();

                    // 3. POLL: Now that execution and updates are done, safely remove it
                    blockEvents.poll();
                }

                if (!active && blockEvents.isEmpty() && neighborUpdates.isEmpty()) {
                    log("§8Simulation settled. Halting early.");
                    break;
                }
            }
        }

        private void flushNeighborUpdates() {
            String previousPhase = currentPhase;
            currentPhase = "NEIGHBOR UPDATES";
            while (!neighborUpdates.isEmpty()) {
                NeighborUpdate update = neighborUpdates.poll();
                BlockState state = getBlockState(update.pos);
                handlePistonUpdate(update.pos, state); // Replaced inline check
            }
            currentPhase = previousPhase;
        }

        public void updateNeighborsAt(BlockPos pos) {
            String previousPhase = currentPhase;
            currentPhase = "NEIGHBOR UPDATES";

            for (Direction dir : UPDATE_ORDER) {
                BlockPos neighborPos = pos.relative(dir);
                BlockState state = getBlockState(neighborPos);
                handlePistonUpdate(neighborPos, state); // Replaced inline check
            }

            currentPhase = previousPhase;
        }

        // NEW HELPER: Routes updates from the extended arm to the base
        private void handlePistonUpdate(BlockPos pos, BlockState state) {
            if (state.getBlock() instanceof PistonBaseBlock) {
                checkIfExtend(pos, state);
            } else if (state.getBlock() instanceof PistonHeadBlock) {
                Direction facing = state.getValue(PistonHeadBlock.FACING);
                BlockPos basePos = pos.relative(facing.getOpposite());
                BlockState baseState = getBlockState(basePos);

                // Instantly pass the update down to the base
                if (baseState.getBlock() instanceof PistonBaseBlock) {
                    checkIfExtend(basePos, baseState);
                }
            }
        }

        public boolean hasRedstoneBlockPower(BlockPos pos, Direction pistonFacing) {
            // Standard adjacent checks (Excluding the front face)
            for (Direction dir : Direction.values()) {
                if (dir == pistonFacing) continue;
                if (getBlockState(pos.relative(dir)).is(Blocks.REDSTONE_BLOCK)) return true;
            }

            // Quasi-connectivity (Checking the block above, excluding DOWN)
            BlockPos above = pos.above();
            for (Direction dir : Direction.values()) {
                if (dir == Direction.DOWN) continue;
                if (getBlockState(above.relative(dir)).is(Blocks.REDSTONE_BLOCK)) return true;
            }

            return false;
        }

        public void checkIfExtend(BlockPos pos, BlockState state) {
            Direction facing = state.getValue(PistonBaseBlock.FACING);
            boolean powered = hasRedstoneBlockPower(pos, facing);
            boolean extended = state.getValue(PistonBaseBlock.EXTENDED);

            if (powered && !extended) {
                SimPistonResolver resolver = new SimPistonResolver(this, pos, facing, true);
                if (resolver.resolve()) {
                    log("Piston powered and path valid at " + pos.toShortString() + ". Queuing Extend.");
                    BlockEvent event = new BlockEvent(pos, state, 0, facing);
                    if (!blockEvents.contains(event)) blockEvents.add(event); // Deduplicate
                }
            } else if (!powered && extended) {
                log("Piston unpowered at " + pos.toShortString() + ". Queuing Contract Event.");
                BlockEvent event = new BlockEvent(pos, state, 1, facing);
                if (!blockEvents.contains(event)) blockEvents.add(event); // Deduplicate
            }
        }

        private void triggerPistonEvent(BlockPos pos, BlockState queuedState, int type, Direction queuedFacing) {
            // 1. Fetch the CURRENT state at the event position
            BlockState currentState = getBlockState(pos);

            // 2. Validate the block is still the exact same piston variant
            // In vanilla, the block event holds a reference to the Block type, and checks it upon execution.
            if (!currentState.is(queuedState.getBlock())) {
                log("§eEvent cancelled: Block at " + pos.toShortString() + " is no longer the correct piston variant.");
                return;
            }

            // 3. Extract properties from the CURRENT state (direction doesn't matter for validation, just execution)
            Direction facing = currentState.getValue(PistonBaseBlock.FACING);
            boolean isPowered = hasRedstoneBlockPower(pos, facing);
            if (type == 0) {
                // EXTEND
                // VANILLA PARITY: Cancel extend if the piston lost power before the event fired
                if (!isPowered) {
                    log("§eExtend cancelled: piston lost power before processing at " + pos.toShortString());
                    return;
                }

                SimPistonResolver resolver = new SimPistonResolver(this, pos, facing, true);
                if (!resolver.resolve()) {
                    log("§cExtend failed: " + resolver.getFailureReason() + " (Pos: " +
                            (resolver.getFailurePos() != null ? resolver.getFailurePos().toShortString() : "N/A") + ")");
                    return;
                }

                // Use currentState here!
                moveBlocks(pos, currentState, facing, true, resolver);
                boolean extendIsSticky = currentState.getBlock() == Blocks.STICKY_PISTON;
                setBlockRaw(pos, currentState.setValue(PistonBaseBlock.EXTENDED, true));

                log("Set block at " + pos.toShortString() + " to "
                        + (extendIsSticky ? "Sticky Piston Headless Base" : "Piston Headless Base"));
                log("Firing neighbor updates for piston base at: " + pos.toShortString());
                updateNeighborsAt(pos);

            } else if (type == 1) {
                // RETRACT
                // VANILLA PARITY: Cancel retract if piston regained power, and force it back to extended
                if (isPowered) {
                    log("§eRetract cancelled: piston re-gained power at " + pos.toShortString());
                    // Vanilla explicitly sets it to extended just in case
                    setBlockRaw(pos, currentState.setValue(PistonBaseBlock.EXTENDED, true));
                    return;
                }

                // Use currentState here!
                boolean isSticky = currentState.getBlock() == Blocks.STICKY_PISTON;
                BlockPos headPos = pos.relative(facing);
                setBlockRaw(headPos, Blocks.AIR.defaultBlockState());

                // 1. If the head slot still holds a moving piston BE, instantly resolve it first
                SimPistonMovingEntity headBE = blockEntities.get(headPos);
                if (headBE != null) {
                    log("Instant finalTick on moving head at " + headPos.toShortString());
                    headBE.finalTick(this);
                }

                // 2. Convert the piston base to a retracting moving piston BE
                PistonType pType = isSticky ? PistonType.STICKY : PistonType.DEFAULT;
                BlockState movingBaseState = Blocks.MOVING_PISTON.defaultBlockState()
                        .setValue(BlockStateProperties.FACING, facing)
                        .setValue(PistonHeadBlock.TYPE, pType);
                BlockState unextendedBase = currentState.setValue(PistonBaseBlock.EXTENDED, false);

                setBlockRaw(pos, movingBaseState);
                blockEntities.put(pos, new SimPistonMovingEntity(pos, unextendedBase, facing, false, true));
                log("Created MovingPiston BE at " + pos.toShortString() + " for Retracting "
                        + (isSticky ? "Sticky " : "") + "Base");

                // 3. Immediately fire neighbor updates at the base position (vanilla: blockUpdated)
                log("Firing neighbor updates for retracting base at: " + pos.toShortString());
                updateNeighborsAt(pos);

                // 4. Handle the head slot and optional block pulling
                if (isSticky) {
                    BlockPos twoAheadPos = pos.relative(facing, 2);
                    SimPistonMovingEntity twoAheadBE = blockEntities.get(twoAheadPos);
                    boolean instantFinished = false;

                    // Catch mid-push blocks
                    if (twoAheadBE != null && twoAheadBE.extending && twoAheadBE.direction == facing) {
                        log("Instant finalTick on mid-push block at " + twoAheadPos.toShortString());
                        twoAheadBE.finalTick(this);
                        instantFinished = true;
                    }

                    if (!instantFinished) {
                        BlockState twoAheadState = getBlockState(twoAheadPos);
                        boolean shouldPull = !twoAheadState.isAir()
                                && isPushableForPull(twoAheadState, twoAheadPos, facing.getOpposite(), facing)
                                && (twoAheadState.getPistonPushReaction() == PushReaction.NORMAL
                                || twoAheadState.is(Blocks.PISTON)
                                || twoAheadState.is(Blocks.STICKY_PISTON));

                        if (shouldPull) {
                            SimPistonResolver resolver = new SimPistonResolver(this, pos, facing, false);

                            if (resolver.resolve()) {
                                // Use currentState here!
                                moveBlocks(pos, currentState, facing, false, resolver);
                            } else {
                                log("§cRetract pull failed: " + resolver.getFailureReason());
                                updateNeighborsAt(headPos);
                            }
                        } else {
                            updateNeighborsAt(headPos);
                        }
                    }
                } else {
                    updateNeighborsAt(headPos);
                }
            }
        }

        private void moveBlocks(BlockPos pistonPos, BlockState pistonState, Direction dir, boolean extending, SimPistonResolver resolver) {

            List<BlockPos> toPush = resolver.toPush;
            List<BlockPos> toDestroy = resolver.toDestroy;

            log("Resolver found " + toPush.size() + " blocks to move, " + toDestroy.size() + " blocks to destroy.");

            for (int i = toDestroy.size() - 1; i >= 0; i--) {
                BlockPos p = toDestroy.get(i);
                setBlock(p, Blocks.AIR.defaultBlockState());
            }

            Map<BlockPos, BlockState> vacatedSpots = new HashMap<>();
            for (BlockPos p : toPush) {
                vacatedSpots.put(p, getBlockState(p));
            }

            Direction moveDir = extending ? dir : dir.getOpposite();
            BlockPos headPos = pistonPos.relative(dir);

            if (!extending && getBlockState(headPos).is(Blocks.PISTON_HEAD)) {
                setBlockRaw(headPos, Blocks.AIR.defaultBlockState());
            }

            for (int i = toPush.size() - 1; i >= 0; i--) {
                BlockPos oldPos = toPush.get(i);
                BlockPos newPos = oldPos.relative(moveDir);
                BlockState movingState = vacatedSpots.get(oldPos);

                vacatedSpots.remove(newPos);

                PistonType type = pistonState.is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT;
                SimPistonMovingEntity be = new SimPistonMovingEntity(newPos, movingState, dir, extending, false);
                blockEntities.put(newPos, be);

                setBlockRaw(newPos, Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, dir).setValue(PistonHeadBlock.TYPE, type));
                log("Created MovingPiston BE at " + newPos.toShortString() + " carrying " + movingState.getBlock().getName().getString());
            }

            if (extending) {
                PistonType type = pistonState.is(Blocks.STICKY_PISTON) ? PistonType.STICKY : PistonType.DEFAULT;
                BlockState headState = Blocks.PISTON_HEAD.defaultBlockState()
                        .setValue(PistonHeadBlock.FACING, dir)
                        .setValue(PistonHeadBlock.TYPE, type);

                vacatedSpots.remove(headPos);
                SimPistonMovingEntity headBE = new SimPistonMovingEntity(headPos, headState, dir, true, true);
                blockEntities.put(headPos, headBE);
                setBlockRaw(headPos, Blocks.MOVING_PISTON.defaultBlockState().setValue(BlockStateProperties.FACING, dir).setValue(PistonHeadBlock.TYPE, type));
                log("Created MovingPiston BE at " + headPos.toShortString() + " carrying PISTON_HEAD");
            }

            for (BlockPos p : vacatedSpots.keySet()) {
                setBlockRaw(p, Blocks.AIR.defaultBlockState());
            }

            for (int i = toPush.size() - 1; i >= 0; i--) {
                log("Firing neighbor updates for vacated pushed pos: " + toPush.get(i).toShortString());
                updateNeighborsAt(toPush.get(i));
            }
            if (extending) {
                log("Firing neighbor updates for moving piston head starting pos: " + headPos.toShortString());
                updateNeighborsAt(headPos);
            }
        }

        /**
         * Clears the head position to air and fires neighbor updates only if the
         * block there was not already air (air→air produces no block update).
         */
        private void clearHeadPos(BlockPos headPos) {
            boolean wasAir = getBlockState(headPos).isAir();
            setBlockRaw(headPos, Blocks.AIR.defaultBlockState());
            if (!wasAir) {
                log("Set " + headPos.toShortString() + " to Air (cleared head)");
                log("Firing neighbor updates for cleared head at: " + headPos.toShortString());
                updateNeighborsAt(headPos);
            }
        }

        /**
         * Full vanilla isPushable semantics for the sticky-piston retract pull check.
         * Mirrors PistonBaseBlock.isPushable with canDestroy=false (retract never destroys).
         *   pushDir      = facing.getOpposite() — direction of movement during the pull
         *   pistonFacing = the piston's own facing direction
         * PUSH_ONLY blocks always return false here because pushDir != pistonFacing when pulling.
         */
        private boolean isPushableForPull(BlockState state, BlockPos pos, Direction pushDir, Direction pistonFacing) {
            if (blockEntities.containsKey(pos)) return false; // Moving Pistons are Block Entities!
            if (state.isAir() || state.is(Blocks.BEDROCK)) return false;
            if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)
                    || state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.REINFORCED_DEEPSLATE)
                    || state.is(Blocks.BEDROCK)) return false;
            if (!state.is(Blocks.PISTON) && !state.is(Blocks.STICKY_PISTON)) {
                switch (state.getPistonPushReaction()) {
                    case BLOCK:     return false;
                    case DESTROY:   return false;              // canDestroy = false for retract
                    case PUSH_ONLY: return pushDir == pistonFacing; // always false when pulling
                }
            } else {
                if (state.getValue(PistonBaseBlock.EXTENDED)) return false;
            }
            return !state.hasBlockEntity();
        }
    }

    // ==========================================
    // SIMULATED MOVING BLOCK ENTITY
    // ==========================================
    static class SimPistonMovingEntity {
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
            level.log("Moving block at " + pos.toShortString() + " progressing: " + progress);

            if (progress >= 1.0f) {
                // Natural completion
                level.blockEntities.remove(pos);
                level.setBlock(pos, movedState);

                if (movedState.getBlock() instanceof PistonBaseBlock) {
                    level.checkIfExtend(pos, movedState);
                }
                level.updateNeighborsAt(pos);
            }
        }

        public void finalTick(VirtualLevel level) {
            // Forced interruption (e.g. block destroyed or piston retracted mid-push)
            level.blockEntities.remove(pos);

            // Vanilla Rule: If this is the Piston Head getting interrupted, it vanishes.
            BlockState stateToPlace = isSourcePiston ? Blocks.AIR.defaultBlockState() : movedState;
            level.setBlock(pos, stateToPlace);

            if (stateToPlace.getBlock() instanceof PistonBaseBlock) {
                level.checkIfExtend(pos, stateToPlace);
            }
            level.updateNeighborsAt(pos);
        }
    }

    // ==========================================
    // VIRTUAL PISTON STRUCTURE RESOLVER
    // ==========================================
    static class SimPistonResolver {
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
                // Capture failure
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
                    // Fix: Reorder list upon slimeblock collision loop!
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
            if (level.blockEntities.containsKey(pos)) return false; // Prevent pushing moving pistons
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

    record BlockEvent(BlockPos pos, BlockState state, int type, Direction dir) {}
    record NeighborUpdate(BlockPos pos, BlockPos fromPos) {}
}