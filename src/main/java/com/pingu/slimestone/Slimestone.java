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
        level.runTickLoop(10);
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
                    BlockEvent event = blockEvents.poll();
                    triggerPistonEvent(event.pos, event.state, event.type, event.dir);
                    flushNeighborUpdates();
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
                if (state.getBlock() instanceof PistonBaseBlock) {
                    checkIfExtend(update.pos, state);
                }
            }
            currentPhase = previousPhase;
        }

        public void updateNeighborsAt(BlockPos pos) {
            for (Direction dir : UPDATE_ORDER) {
                neighborUpdates.add(new NeighborUpdate(pos.relative(dir), pos));
            }
        }

        public boolean hasRedstoneBlockPower(BlockPos pos) {
            for (Direction dir : Direction.values()) {
                if (getBlockState(pos.relative(dir)).is(Blocks.REDSTONE_BLOCK)) return true;
            }
            return false;
        }

        public void checkIfExtend(BlockPos pos, BlockState state) {
            boolean powered = hasRedstoneBlockPower(pos);
            boolean extended = state.getValue(PistonBaseBlock.EXTENDED);
            Direction facing = state.getValue(PistonBaseBlock.FACING);

            if (powered && !extended) {
                // 1. Instantiate and VALIDATE before queuing
                SimPistonResolver resolver = new SimPistonResolver(this, pos, facing, true);
                if (resolver.resolve()) {
                    log("Piston powered and path valid at " + pos.toShortString() + ". Queuing Extend.");
                    blockEvents.add(new BlockEvent(pos, state, 0, facing));
                } else {
                    log("§cExtend blocked: Path invalid at " + pos.toShortString());
                }
            } else if (!powered && extended) {
                log("Piston unpowered at " + pos.toShortString() + ". Queuing Contract Event (1).");
                blockEvents.add(new BlockEvent(pos, state, 1, facing));
            }
        }

        private void triggerPistonEvent(BlockPos pos, BlockState state, int type, Direction facing) {
            if (type == 0) {
                // EXTEND
                SimPistonResolver resolver = new SimPistonResolver(this, pos, facing, true);
                if (!resolver.resolve()) {
                    // Print the detailed error
                    log("§cExtend failed: " + resolver.getFailureReason() + " (Pos: " +
                            (resolver.getFailurePos() != null ? resolver.getFailurePos().toShortString() : "N/A") + ")");
                    return;
                }
                moveBlocks(pos, state, facing, true, resolver);
                boolean extendIsSticky = state.getBlock() == Blocks.STICKY_PISTON;
                setBlockRaw(pos, state.setValue(PistonBaseBlock.EXTENDED, true));
                log("Set block at " + pos.toShortString() + " to "
                        + (extendIsSticky ? "Sticky Piston Headless Base" : "Piston Headless Base"));
                log("Firing neighbor updates for piston base at: " + pos.toShortString());
                updateNeighborsAt(pos);

            } else if (type == 1) {
                // RETRACT
                // Cancel if the piston re-gained power before this event fires
                if (hasRedstoneBlockPower(pos)) {
                    log("§eRetract cancelled: piston re-gained power at " + pos.toShortString());
                    return;
                }

                boolean isSticky = state.getBlock() == Blocks.STICKY_PISTON;
                BlockPos headPos = pos.relative(facing);

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
                BlockState unextendedBase = state.setValue(PistonBaseBlock.EXTENDED, false);

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

                    // If a block is mid-push 2 ahead (extending, same direction) → instantly finish it
                    SimPistonMovingEntity twoAheadBE = blockEntities.get(twoAheadPos);
                    boolean instantFinished = false;
                    if (twoAheadBE != null && twoAheadBE.extending && twoAheadBE.direction == facing) {
                        log("Instant finalTick on mid-push block at " + twoAheadPos.toShortString());
                        twoAheadBE.finalTick(this);
                        instantFinished = true;
                    }

                    if (!instantFinished) {
                        // Vanilla pull condition (mirrors PistonBaseBlock.triggerEvent type-1 branch)
                        BlockState twoAheadState = getBlockState(twoAheadPos);
                        boolean shouldPull = !twoAheadState.isAir()
                                && isPushableForPull(twoAheadState, twoAheadPos, facing.getOpposite(), facing)
                                && (twoAheadState.getPistonPushReaction() == PushReaction.NORMAL
                                || twoAheadState.is(Blocks.PISTON)
                                || twoAheadState.is(Blocks.STICKY_PISTON));

                        if (shouldPull) {
                            clearHeadPos(headPos);
                            SimPistonResolver resolver = new SimPistonResolver(this, pos, facing, false);
                            if (resolver.resolve()) {
                                moveBlocks(pos, state, facing, false, resolver);
                            } else {
                                log("§cRetract pull failed: " + resolver.getFailureReason());
                            }
                        } else {
                            log("Not pulling: conditions not met at " + twoAheadPos.toShortString());
                            clearHeadPos(headPos);
                        }
                    }
                } else {
                    // Normal piston: always clear the head position
                    clearHeadPos(headPos);
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
            if (state.isAir()) return false;
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
                finalTick(level);
            }
        }

        public void finalTick(VirtualLevel level) {
            level.blockEntities.remove(pos);
            level.setBlock(pos, movedState);
            level.log("Block arrived: " + movedState.getBlock().getName().getString() + " at " + pos.toShortString());

            // Log and fire neighbor updates mirroring vanilla `tick()` placing the block
            level.log("Firing neighbor updates for arrived block at: " + pos.toShortString());
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

            if (!isPushable(startState, pushDirection)) {
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
            if (state.isAir() || !isPushable(state, dir) || pos.equals(pistonPos) || toPush.contains(pos)) return true;

            int count = 1;
            while (isSticky(state)) {
                BlockPos next = pos.relative(pushDirection.getOpposite(), count);
                BlockState nextState = level.getBlockState(next);
                if (nextState.isAir() || !canStickToEachOther(state, nextState) || pos.equals(pistonPos)) break;
                state = nextState;
                count++;
                if (count + toPush.size() > 12) {
                    this.failurePos = pos;
                    this.failureReason = "Piston push limit (12) reached";
                    return false;
                }
            }

            for (int i = count - 1; i >= 0; i--) {
                toPush.add(pos.relative(pushDirection.getOpposite(), i));
            }

            int step = 1;
            while (true) {
                BlockPos nextPos = pos.relative(pushDirection, step);
                if (toPush.contains(nextPos)) return true;

                BlockState nextState = level.getBlockState(nextPos);
                if (nextState.isAir()) return true;

                if (!isPushable(nextState, pushDirection) || nextPos.equals(pistonPos)) {
                    this.failurePos = nextPos;
                    this.failureReason = "Blocked by unpushable block/world at " + nextPos.toShortString();
                    return false;
                }

                if (nextState.getPistonPushReaction() == PushReaction.DESTROY) {
                    toDestroy.add(nextPos);
                    return true;
                }

                if (toPush.size() >= 12) {
                    this.failurePos = nextPos;
                    this.failureReason = "Piston push limit (12) reached";
                    return false;
                }
                toPush.add(nextPos);
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

        private boolean isPushable(BlockState state, Direction dir) {
            if (state.isAir() || state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN) || state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.REINFORCED_DEEPSLATE)) return false;

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