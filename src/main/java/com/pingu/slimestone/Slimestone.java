package com.pingu.slimestone;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public class Slimestone implements ModInitializer {

    private static Slimestone INSTANCE;

    private static boolean trackingEnabled = false;
    private static BlockPos trackedOrigin = null;
    private static ServerPlayer trackedPlayer = null;

    // Set whenever a placement/break reports a world change. The actual
    // re-simulation is deferred until the real world stops doing anything
    // that would still change on its own - checked once per tick below.
    private static boolean pendingSimRun = false;

    @Override
    public void onInitialize() {
        INSTANCE = this;

        // Tick listener
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long tick = server.overworld().getGameTime();
            ObserverDebugger.onServerTick(tick);
            PistonDebugger.onServerTick(tick);
            MovingBlockDebugger.onServerTick(tick);

            if (pendingSimRun && trackingEnabled && trackedOrigin != null && trackedPlayer != null) {
                if (isWorldSettled(trackedPlayer, trackedOrigin)) {
                    pendingSimRun = false;
                    runSimulation(trackedPlayer, trackedOrigin);
                }
                // else: a piston is still travelling, or an observer/lamp has a
                // delayed tick still pending - stay pending and check again
                // next tick.
            }
        });

        // Re-run when a block is removed
        PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
            if (trackingEnabled) {
                pendingSimRun = true;
            }
        });

        // Commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("slimestone")

                    // /slimestone  -> toggle current session on/off
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();

                        if (trackingEnabled) {
                            stopTracking(player);
                            return 1;
                        }

                        BlockPos target = getTargetBlock(player);
                        if (target == null) {
                            context.getSource().sendFailure(Component.literal(
                                    "You must be looking at a block, or specify coordinates!"));
                            return 0;
                        }

                        startTracking(player, target);
                        return 1;
                    })

                    // /slimestone <pos> -> start/retarget session at explicit coordinates
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                BlockPos target = BlockPosArgument.getLoadedBlockPos(context, "pos");
                                startTracking(player, target);
                                return 1;
                            })
                    )

                    .then(Commands.literal("compare")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();

                                boolean obsActive = ObserverDebugger.recordState == ObserverDebugger.RecordState.AWAITING
                                        || ObserverDebugger.recordState == ObserverDebugger.RecordState.RECORDING;
                                boolean pisActive = PistonDebugger.recordState == PistonDebugger.RecordState.AWAITING
                                        || PistonDebugger.recordState == PistonDebugger.RecordState.RECORDING;
                                boolean movActive = MovingBlockDebugger.recordState == MovingBlockDebugger.RecordState.AWAITING
                                        || MovingBlockDebugger.recordState == MovingBlockDebugger.RecordState.RECORDING;

                                if (!obsActive && !pisActive && !movActive) {
                                    //player.sendSystemMessage(Component.literal(
                                    //        "§c[Slimestone] No active recording. Run §f/slimestone§c first."));
                                    return 0;
                                }

                                if (obsActive) {
                                    ObserverDebugger.trackedPlayer = player;
                                    ObserverDebugger.finishRecording();
                                }
                                if (pisActive) {
                                    PistonDebugger.trackedPlayer = player;
                                    PistonDebugger.finishRecording();
                                }
                                if (movActive) {
                                    MovingBlockDebugger.trackedPlayer = player;
                                    MovingBlockDebugger.finishRecording();
                                }
                                return 1;
                            })
                    )

                    .then(Commands.literal("reset")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                stopTracking(player);
                                ObserverDebugger.fullReset(player);
                                PistonDebugger.fullReset(player);
                                MovingBlockDebugger.fullReset(player);
                                return 1;
                            })
                    )
            );
        });
    }

    private void startTracking(ServerPlayer player, BlockPos origin) {
        trackedPlayer = player;
        trackedOrigin = origin.immutable();
        trackingEnabled = true;

        player.sendSystemMessage(Component.literal(
                "§a[Slimestone] Tracking enabled at " + trackedOrigin.toShortString()));

        runSimulation(player, trackedOrigin);
    }

    private void stopTracking(ServerPlayer player) {
        trackingEnabled = false;
        trackedOrigin = null;
        trackedPlayer = null;
        pendingSimRun = false;

        player.sendSystemMessage(Component.literal("§e[Slimestone] Tracking disabled."));
    }

    /**
     * True once the real world around {@code origin} has stopped doing
     * anything that would still change on its own next tick. Placing or
     * breaking a block can kick off vanilla updates that span several ticks -
     * a piston takes 2 ticks to finish travelling, an observer's pulse takes
     * 2 ticks to fall, a redstone lamp's turn-off is delayed 4 ticks - so
     * running the simulation the instant a block changes can snapshot a
     * transient, not-yet-settled state. This scans the same region the
     * simulation itself captures for a piston still mid-travel (a
     * MOVING_PISTON block present) or an observer/lamp with a scheduled tick
     * still pending.
     */
    private boolean isWorldSettled(ServerPlayer player, BlockPos origin) {
        Level realLevel = player.level();

        for (BlockPos p : computeContraptionRegion(realLevel, origin)) {
            BlockState state = realLevel.getBlockState(p);

            if (state.is(Blocks.MOVING_PISTON)) {
                return false;
            }
            if (state.is(Blocks.OBSERVER) && realLevel.getBlockTicks().hasScheduledTick(p, Blocks.OBSERVER)) {
                return false;
            }
            if (state.is(Blocks.REDSTONE_LAMP) && realLevel.getBlockTicks().hasScheduledTick(p, Blocks.REDSTONE_LAMP)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the set of positions that make up the contraption at {@code origin},
     * instead of scanning a fixed cube. Starts from a 5x5x5 cube (radius 2)
     * around the origin, always fully included. From there it flood-fills
     * outward: every non-air block queues up its surrounding 3x3x3 cube, plus
     * the six positions exactly 2 blocks away along a single axis (+x/-x,
     * +y/-y, +z/-z). A block that is air is still captured - so a piston has
     * room to push into it - but it's a dead end: the fill does not expand
     * past it. Only non-air blocks propagate the search further.
     */
    private static Set<BlockPos> computeContraptionRegion(Level level, BlockPos origin) {
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos p = origin.offset(x, y, z);
                    if (visited.add(p)) {
                        queue.add(p);
                    }
                }
            }
        }

        while (!queue.isEmpty()) {
            BlockPos p = queue.poll();

            // Air is captured but is not a valid expansion point - stop here.
            if (level.getBlockState(p).isAir()) {
                continue;
            }

            // 3x3x3 cube around p.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos n = p.offset(dx, dy, dz);
                        if (visited.add(n)) {
                            queue.add(n);
                        }
                    }
                }
            }

            // Plus the six positions exactly 2 away along a single axis.
            BlockPos[] axisExtensions = {
                    p.offset(2, 0, 0), p.offset(-2, 0, 0),
                    p.offset(0, 2, 0), p.offset(0, -2, 0),
                    p.offset(0, 0, 2), p.offset(0, 0, -2)
            };
            for (BlockPos n : axisExtensions) {
                if (visited.add(n)) {
                    queue.add(n);
                }
            }
        }

        return visited;
    }

    public static void onWorldChanged() {
        if (INSTANCE != null && trackingEnabled) {
            pendingSimRun = true;
        }
    }

    // ── Simulation ────────────────────────────────────────────────────────────
    private void runSimulation(ServerPlayer player, BlockPos targetPos) {
        //player.sendSystemMessage(Component.literal(
        //        "§a[Slimestone] Starting virtual simulation at " + targetPos.toShortString()));

        ObserverDebugger.resetExpected();
        PistonDebugger.resetExpected();
        MovingBlockDebugger.resetExpected();

        VirtualLevel level = new VirtualLevel(player);

        for (BlockPos p : computeContraptionRegion(player.level(), targetPos)) {
            level.setBlockRaw(p, player.level().getBlockState(p));
        }

        BlockState targetState = level.getBlockState(targetPos);

        if (targetState.getBlock() instanceof PistonBaseBlock) {
            //level.log("Checking initial power for piston at " + targetPos.toShortString());
            level.checkIfExtend(targetPos, targetState);
        } else if (targetState.is(Blocks.OBSERVER)) {
            //level.log("Observer at " + targetPos.toShortString() + " — simulating artificial update");
            level.scheduleTick(targetPos, Blocks.OBSERVER, 2);
        } else {
            //player.sendSystemMessage(Component.literal(
            //        "§c[Slimestone] Target is not a piston or observer!"));
            return;
        }

        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput(),
                "kill @e[type=minecraft:text_display]"
        );

        level.runTickLoop(1000);

        int expCount = ObserverDebugger.expected.size();
        //player.sendSystemMessage(Component.literal(
        //        "§e[Slimestone] Simulation predicted §f" + expCount
        //                + "§e observer events in the first §f" + ObserverDebugger.RECORD_GT + "§e GT:"));

        if (expCount == 0) {
            //player.sendSystemMessage(Component.literal("§7  (none — no observers fired in this window)"));
        } else {
            for (int i = 0; i < expCount; i++) {
                ObserverDebugger.ObserverEvent e = ObserverDebugger.expected.get(i);
                String stateStr = e.poweredOn() ? "§aON" : "§cOFF";
                //player.sendSystemMessage(Component.literal(
                //        "§7  #" + (i + 1) + "  GT" + e.gt() + "  "
                //                + e.pos().toShortString() + "  " + stateStr));
            }
        }

        int expPisCount = PistonDebugger.expected.size();
        //player.sendSystemMessage(Component.literal(
        //        "§b[Slimestone] Simulation predicted §f" + expPisCount
        //                + "§b piston events in the first §f" + PistonDebugger.RECORD_GT + "§b GT:"));

        if (expPisCount == 0) {
            //player.sendSystemMessage(Component.literal("§7  (none — no pistons fired in this window)"));
        } else {
            for (int i = 0; i < expPisCount; i++) {
                PistonDebugger.PistonEvent e = PistonDebugger.expected.get(i);
                String stateStr = e.extending() ? "§bEXTEND" : "§6RETRACT";
                //player.sendSystemMessage(Component.literal(
                //        "§7  #" + (i + 1) + "  GT" + e.gt() + "  "
                //                + e.pos().toShortString() + "  " + stateStr));
            }
        }

        int expMovCount = MovingBlockDebugger.expected.size();
        //player.sendSystemMessage(Component.literal(
        //        "§d[Slimestone] Simulation predicted §f" + expMovCount
        //                + "§d moving block events in the first §f" + MovingBlockDebugger.RECORD_GT + "§d GT:"));

        if (expMovCount == 0) {
            //player.sendSystemMessage(Component.literal("§7  (none — no moving blocks fired in this window)"));
        } else {
            for (int i = 0; i < expMovCount; i++) {
                MovingBlockDebugger.MovingBlockEvent e = MovingBlockDebugger.expected.get(i);
                String stateStr = e.extending() ? "§bEXTEND" : "§6RETRACT";
                //player.sendSystemMessage(Component.literal(
                //        "§7  #" + (i + 1) + "  GT" + e.gt() + "  "
                //                + e.pos().toShortString() + "  " + stateStr + " §8[" + e.blockName() + "]"));
            }
        }

        ObserverDebugger.prepareForRealRecording(player);
        PistonDebugger.prepareForRealRecording(player);
        MovingBlockDebugger.prepareForRealRecording(player);

        //player.sendSystemMessage(Component.literal(
        //        "§a[Slimestone] Ready — run your machine! (auto-compare after §f"
        //                + ObserverDebugger.RECORD_GT + "§a GT, or type §f/slimestone compare§a)"));
    }

    private BlockPos getTargetBlock(ServerPlayer player) {
        double distance = 10.0;
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getViewVector(1.0f);
        Vec3 endPos = eyePos.add(lookVec.x * distance, lookVec.y * distance, lookVec.z * distance);

        ClipContext ctx = new ClipContext(
                eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        BlockHitResult hit = player.level().clip(ctx);

        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private void spawnNumberDisplay(ServerPlayer player, BlockPos targetPos) {
        double x = targetPos.getX() + 0.5;
        double y = targetPos.getY() + 0.5;
        double z = targetPos.getZ() + 0.5;

        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack(),
                "kill @e[type=minecraft:text_display]"
        );

        String displayText = "Gt 0\n3a\n12";
        String jsonText = displayText
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");

        String summon = String.format(
                java.util.Locale.US,
                "summon minecraft:text_display %.3f %.3f %.3f {text:'{\"text\":\"%s\"}',billboard:\"center\",see_through:1b,shadow:0b,default_background:1b,alignment:\"center\",line_width:200}",
                x, y, z, jsonText
        );

// Chain .withSuppressedOutput() here to silence the command feedback
        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput(),
                summon
        );

        //player.sendSystemMessage(Component.literal("§a[Slimestone] Placed text display:\nGt 0\n3a\n12"));
    }
}