package com.pingu.slimestone;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Slimestone implements ModInitializer {

    private static Slimestone INSTANCE;

    private static boolean trackingEnabled = false;
    private static BlockPos trackedOrigin = null;
    private static ServerPlayer trackedPlayer = null;
    private static final List<BlockPos> startingInputs = new ArrayList<>();

    // Set whenever a placement/break reports a world change. The actual
    // re-simulation is deferred until the real world stops doing anything
    // that would still change on its own - checked once per tick below.
    private static boolean pendingSimRun = false;

    private enum RotationCheckMode { OFF, ALL, HALF_TURN }

    // ALL compares 90/180/270-degree copies; HALF_TURN compares only 180°.
    private static RotationCheckMode rotationCheckMode = RotationCheckMode.ALL;

    // The VirtualLevel from the most recently completed simulation run, kept
    // around so /slimestone poi <gt> can look up its recorded actions.
    private static VirtualLevel lastSimLevel = null;

    // Remembers the last window used with /poi <gt> <window>, so a bare
    // /poi <gt> can reuse it. Starts at 3 until the player sets one.
    private static int lastPoiWindow = 3;

    // True whenever the last thing the player asked to see was a windowed
    // /poi <gt> [window] view, rather than the full end-of-run display. While
    // this is true, a resimulation triggered by a world change re-renders the
    // same POI window instead of falling back to the full display, and the
    // displacement ("." / "█") display system is skipped entirely. Only a
    // bare /poi (no arguments) turns this back off.
    private static boolean poiModeActive = false;
    private static int lastPoiGt = 0;

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

        // Integrated servers stop whenever the player leaves a singleplayer
        // world. Clear every session reference before the next world loads.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> resetServerSession(trackedPlayer));

        // A multiplayer server stays running after a player leaves, so also
        // clean up when the player who owns the active simulation disconnects.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayer disconnectingPlayer = handler.getPlayer();
            if (disconnectingPlayer == trackedPlayer
                    || disconnectingPlayer == ObserverDebugger.trackedPlayer
                    || disconnectingPlayer == PistonDebugger.trackedPlayer
                    || disconnectingPlayer == MovingBlockDebugger.trackedPlayer) {
                resetServerSession(disconnectingPlayer);
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

                    // /slimestone -> start at the looked-at block, retarget to
                    // another redstone component, or stop on a non-redstone block.
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();

                        if (trackingEnabled) {
                            BlockPos target = getTargetBlock(player);
                            if (target != null
                                    && !target.equals(trackedOrigin)
                                    && isRedstoneComponent(player.level().getBlockState(target))) {
                                startTracking(player, target);
                            } else {
                                stopTracking(player);
                            }
                            return 1;
                        }

                        BlockPos target = getTargetBlock(player);
                        if (target == null) {
                            context.getSource().sendFailure(Component.literal(
                                    "You must be looking at a block within 50 blocks, or specify coordinates!"));
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

                    // /slimestone add [pos] -> add an input from the raycast or explicit coordinates.
                    .then(Commands.literal("add")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                return addStartingInput(player, getTargetBlock(player));
                            })
                            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                    .executes(context -> {
                                        ServerPlayer player = context.getSource().getPlayerOrException();
                                        return addStartingInput(player,
                                                BlockPosArgument.getLoadedBlockPos(context, "pos"));
                                    })
                            )
                    )

                    // /slimestone remove [pos] -> remove a raycast or explicit input position.
                    .then(Commands.literal("remove")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                return removeStartingInput(player, getTargetBlock(player));
                            })
                            .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                    .executes(context -> {
                                        ServerPlayer player = context.getSource().getPlayerOrException();
                                        return removeStartingInput(player,
                                                BlockPosArgument.getLoadedBlockPos(context, "pos"));
                                    })
                            )
                    )

                    // Internal command used by the client-side F14 keybind.
                    .then(Commands.literal("cycle")
                            .executes(context -> cycleStartingInput(
                                    context.getSource().getPlayerOrException()))
                    )

                    .then(Commands.literal("text1")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                BlockPos target = getTargetBlock(player);
                                if (target == null) {
                                    context.getSource().sendFailure(Component.literal(
                                            "You must be looking at a block!"));
                                    return 0;
                                }
                                spawnNumberDisplay(player, target);
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

                    // /slimestone pushlimit -> show the current simulated push limit
                    // /slimestone pushlimit <X> -> override it (sim-only; the real
                    // game's 12-block limit is untouched - use a separate mod for that).
                    // If a session is currently tracking, this triggers a resimulation
                    // so the change is reflected immediately.
                    .then(Commands.literal("pushlimit")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                player.sendSystemMessage(Component.literal(
                                        "§a[Slimestone] Simulated push limit: §f" + SimPistonResolver.MAX_PUSH
                                                + "§a (vanilla default: §f" + SimPistonResolver.DEFAULT_MAX_PUSH + "§a)"));
                                return SimPistonResolver.MAX_PUSH;
                            })
                            .then(Commands.argument("limit", IntegerArgumentType.integer(1))
                                    .executes(context -> {
                                        ServerPlayer player = context.getSource().getPlayerOrException();
                                        int limit = IntegerArgumentType.getInteger(context, "limit");
                                        SimPistonResolver.setMaxPush(limit);

                                        player.sendSystemMessage(Component.literal(
                                                "§a[Slimestone] Simulated push limit set to §f" + limit
                                                        + "§a (sim-only)."));

                                        if (trackingEnabled && trackedOrigin != null) {
                                            pendingSimRun = true;
                                        }
                                        return limit;
                                    })
                            )
                    )

                    // /slimestone rotationcheck -> show current rotation check mode.
                    // /slimestone rotationcheck all|180|off selects a mode.
                    .then(Commands.literal("rotationcheck")
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                player.sendSystemMessage(Component.literal(
                                        "§a[Slimestone] Rotation check mode: §f"
                                                + getRotationCheckModeLabel()));
                                return 1;
                            })
                            .then(Commands.literal("all")
                                    .executes(context -> setRotationCheckMode(context.getSource().getPlayerOrException(), RotationCheckMode.ALL)))
                            .then(Commands.literal("180")
                                    .executes(context -> setRotationCheckMode(context.getSource().getPlayerOrException(), RotationCheckMode.HALF_TURN)))
                            .then(Commands.literal("off")
                                    .executes(context -> setRotationCheckMode(context.getSource().getPlayerOrException(), RotationCheckMode.OFF)))
                    )
            );

            // /poi <gt> [window] -> re-summon only the actions from the last
            // simulation that happened within <window> GT of <gt>. If window
            // is omitted, reuses whatever window was last used (default 3).
            // /poi with no arguments at all resets back to the full end-of-run
            // display (every piston/observer that had an action), same as
            // what /slimestone always used to show before windowed POIs existed.
            dispatcher.register(Commands.literal("poi")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();

                        if (lastSimLevel == null) {
                            context.getSource().sendFailure(Component.literal(
                                    "§c[Slimestone] No simulation has been run yet."));
                            return 0;
                        }

                        poiModeActive = false;
                        int shown = lastSimLevel.spawnFullDisplay();

                        player.sendSystemMessage(Component.literal(
                                "§a[Slimestone] Showing full run - §f" + shown + "§a display(s)."));
                        return 1;
                    })
                    .then(Commands.argument("gt", IntegerArgumentType.integer())
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();

                                if (lastSimLevel == null) {
                                    context.getSource().sendFailure(Component.literal(
                                            "§c[Slimestone] No simulation has been run yet."));
                                    return 0;
                                }

                                int gt = IntegerArgumentType.getInteger(context, "gt");
                                poiModeActive = true;
                                lastPoiGt = gt;
                                int shown = lastSimLevel.spawnPointOfInterest(gt, lastPoiWindow);

                                player.sendSystemMessage(Component.literal(
                                        "§a[Slimestone] Showing §f" + shown
                                                + "§a display(s) within §f" + lastPoiWindow + "§a GT of §fGT " + gt));
                                return 1;
                            })
                            .then(Commands.argument("window", IntegerArgumentType.integer())
                                    .executes(context -> {
                                        ServerPlayer player = context.getSource().getPlayerOrException();

                                        if (lastSimLevel == null) {
                                            context.getSource().sendFailure(Component.literal(
                                                    "§c[Slimestone] No simulation has been run yet."));
                                            return 0;
                                        }

                                        int gt = IntegerArgumentType.getInteger(context, "gt");
                                        int window = IntegerArgumentType.getInteger(context, "window");
                                        poiModeActive = true;
                                        lastPoiGt = gt;
                                        lastPoiWindow = window;
                                        int shown = lastSimLevel.spawnPointOfInterest(gt, window);

                                        player.sendSystemMessage(Component.literal(
                                                "§a[Slimestone] Showing §f" + shown
                                                        + "§a display(s) within §f" + window + "§a GT of §fGT " + gt));
                                        return 1;
                                    })
                            )
                    )
            );

            // /w -> turn every red stained glass block that the last simulation
            // moved exactly once (net displacement 1) into white stained glass,
            // in the real world, without sending a neighbor update. Prints the
            // number converted to chat, followed by the running total number of
            // white stained glass blocks now in the tracked contraption region.
            dispatcher.register(Commands.literal("w")
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();

                        if (lastSimLevel == null) {
                            context.getSource().sendFailure(Component.literal(
                                    "§c[Slimestone] No simulation has been run yet."));
                            return 0;
                        }

                        int changed = lastSimLevel.convertMovedOnceRedGlassToWhite();
                        int totalWhite = lastSimLevel.countWhiteStainedGlassInRegion();

                        player.sendSystemMessage(Component.literal(
                                "§a[Slimestone] Converted §f" + changed
                                        + "§a red stained glass block(s) to white."));
                        player.sendSystemMessage(Component.literal(
                                "§a[Slimestone] Total white stained glass block(s): §f" + totalWhite));
                        return changed;
                    })
            );
        });
    }

    private int setRotationCheckMode(ServerPlayer player, RotationCheckMode mode) {
        rotationCheckMode = mode;
        player.sendSystemMessage(Component.literal("§a[Slimestone] Rotation check mode: §f"
                + getRotationCheckModeLabel()));
        if (trackingEnabled && trackedOrigin != null) {
            pendingSimRun = true;
        }
        return 1;
    }

    private static String getRotationCheckModeLabel() {
        return switch (rotationCheckMode) {
            case OFF -> "OFF";
            case ALL -> "ALL (90°, 180°, 270°)";
            case HALF_TURN -> "180° only";
        };
    }

    private int addStartingInput(ServerPlayer player, BlockPos target) {
        if (target == null) {
            player.sendSystemMessage(Component.literal(
                    "§c[Slimestone] Look at a block within 50 blocks or provide coordinates."));
            return 0;
        }

        BlockPos input = target.immutable();
        if (startingInputs.contains(input)) {
            player.sendSystemMessage(Component.literal(
                    "§e[Slimestone] Starting input already exists at " + input.toShortString()));
            return 0;
        }

        startingInputs.add(input);
        player.sendSystemMessage(Component.literal(
                "§a[Slimestone] Added starting input §f" + startingInputs.size()
                        + "§a at " + input.toShortString()));

        if (!trackingEnabled) {
            startTracking(player, startingInputs.getFirst());
            sendStartingInputSelection(player, 0);
        }
        return 1;
    }

    private int removeStartingInput(ServerPlayer player, BlockPos target) {
        if (target == null) {
            player.sendSystemMessage(Component.literal(
                    "§c[Slimestone] Look at a block within 50 blocks or provide coordinates."));
            return 0;
        }

        BlockPos input = target.immutable();
        int removedIndex = startingInputs.indexOf(input);
        if (removedIndex < 0) {
            player.sendSystemMessage(Component.literal(
                    "§e[Slimestone] No starting input at " + input.toShortString()));
            return 0;
        }

        startingInputs.remove(removedIndex);
        player.sendSystemMessage(Component.literal(
                "§a[Slimestone] Removed starting input at " + input.toShortString()
                        + " (§f" + startingInputs.size() + "§a remaining)."));

        if (trackingEnabled && input.equals(trackedOrigin)) {
            if (startingInputs.isEmpty()) {
                stopTracking(player);
            } else {
                int nextIndex = Math.min(removedIndex, startingInputs.size() - 1);
                startTracking(player, startingInputs.get(nextIndex));
                sendStartingInputSelection(player, nextIndex);
            }
        }
        return 1;
    }

    private int cycleStartingInput(ServerPlayer player) {
        if (startingInputs.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "§c[Slimestone] No starting inputs. Add one with §f/slimestone add§c."));
            return 0;
        }

        int currentIndex = startingInputs.indexOf(trackedOrigin);
        int nextIndex = currentIndex < 0 ? 0 : (currentIndex + 1) % startingInputs.size();
        startTracking(player, startingInputs.get(nextIndex));
        sendStartingInputSelection(player, nextIndex);
        return 1;
    }

    private void sendStartingInputSelection(ServerPlayer player, int selectedIndex) {
        player.sendSystemMessage(Component.literal(
                "§a[Slimestone] Starting input §f" + (selectedIndex + 1) + "/" + startingInputs.size()
                        + "§a selected."));
    }

    private void startTracking(ServerPlayer player, BlockPos origin) {
        boolean wasTracking = trackingEnabled;
        trackedPlayer = player;
        trackedOrigin = origin.immutable();
        trackingEnabled = true;
        pendingSimRun = false;
        poiModeActive = false;

        player.sendSystemMessage(Component.literal(
                "§a[Slimestone] " + (wasTracking ? "Simulation point moved to " : "Tracking enabled at ")
                        + trackedOrigin.toShortString()));

        runSimulation(player, trackedOrigin);
    }

    private void stopTracking(ServerPlayer player) {
        trackingEnabled = false;
        trackedOrigin = null;
        trackedPlayer = null;
        pendingSimRun = false;
        poiModeActive = false;

        killAllDisplayEntities(player);

        player.sendSystemMessage(Component.literal("§e[Slimestone] Tracking disabled."));
    }

    private void resetServerSession(ServerPlayer displayOwner) {
        // Remove simulation displays while the departing player's world is
        // still available. Suppressed command output avoids sending packets to
        // a connection that is already disconnecting.
        if (displayOwner != null) {
            killAllDisplayEntities(displayOwner);
        }

        trackingEnabled = false;
        trackedOrigin = null;
        trackedPlayer = null;
        pendingSimRun = false;
        poiModeActive = false;
        startingInputs.clear();
        lastSimLevel = null;
        lastPoiGt = 0;

        ObserverDebugger.fullReset(null);
        PistonDebugger.fullReset(null);
        MovingBlockDebugger.fullReset(null);
    }

    // Kills every display entity type (text, block, and item displays), not
    // just the text_display rows the simulation itself uses - a clean stop
    // should leave nothing behind regardless of what spawned it.
    private void killAllDisplayEntities(ServerPlayer player) {
        for (String type : new String[] {"text_display", "block_display", "item_display"}) {
            player.getServer().getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withSuppressedOutput(),
                    "kill @e[type=minecraft:" + type + "]"
            );
        }
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
     * outward: every expandable non-air block queues up its surrounding 3x3x3 cube, plus
     * the six positions exactly 2 blocks away along a single axis (+x/-x,
     * +y/-y, +z/-z). A block that is air is still captured - so a piston has
     * room to push into it - but it's a dead end: the fill does not expand
     * past it. Liquids and blocks with the DESTROY piston push reaction are dead ends and are
     * omitted from the simulation snapshot. Bedrock and blocks in the vanilla
     * overworld/nether carver-replaceable tags are captured but are also dead
     * ends. These tags include common terrain and large natural patches such as
     * sand, sandstone, gravel, granite, diorite, and andesite.
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

            BlockState state = level.getBlockState(p);

            // Air, liquids, DESTROY-reaction blocks, and terrain boundaries are
            // captured as positions, but do not let the fill spread beyond them.
            if (state.isAir()
                    || state.getBlock() instanceof LiquidBlock
                    || state.getPistonPushReaction() == PushReaction.DESTROY
                    || isTerrainExpansionBoundary(state)) {
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

    private static boolean isTerrainExpansionBoundary(BlockState state) {
        return state.is(Blocks.BEDROCK)
                || state.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES)
                || state.is(BlockTags.NETHER_CARVER_REPLACEABLES);
    }

    public static void onWorldChanged() {
        if (INSTANCE != null && trackingEnabled) {
            pendingSimRun = true;
        }
    }

    // The tick budget passed to VirtualLevel#simulate for both the real-orientation
    // run and the rotated comparison run.
    private static final int SIM_MAX_TICKS = 1000;

    // Seeds the same GT-0 trigger event runSimulation always used to kick off a
    // run - factored out so both the real level and the throwaway rotated level
    // start from an identical event. Purely block-type driven (instanceof/.is),
    // so it's unaffected by whether targetState has been rotated.
    private static void seedTrigger(VirtualLevel level, BlockPos targetPos) {
        BlockState targetState = level.getBlockState(targetPos);

        if (targetState.getBlock() instanceof PistonBaseBlock) {
            level.checkIfExtend(targetPos, targetState);
        } else if (targetState.is(Blocks.OBSERVER)) {
            level.scheduleTick(targetPos, Blocks.OBSERVER, 2);
        } else {
            // Note block, trapdoor, or fence gate: pretend its redstone state
            // just flipped. Anything else (glass, wool, etc.): pretend the
            // block itself just got broken/removed. Either way this is
            // scheduled as a tile tick at GT 0 (see
            // VirtualLevel#processScheduledTick), so it runs through the
            // normal tick loop instead of jumping the queue.
            level.scheduleTick(targetPos, targetState.getBlock(), 0);
        }
    }

    // ── Simulation ────────────────────────────────────────────────────────────
    private void runSimulation(ServerPlayer player, BlockPos targetPos) {
        //player.sendSystemMessage(Component.literal(
        //        "§a[Slimestone] Starting virtual simulation at " + targetPos.toShortString()));

        // Snapshot the real-world region once - both the real-orientation level
        // and the rotated comparison level are built from this same data, just
        // transformed differently for the latter.
        Map<BlockPos, BlockState> regionSnapshot = new HashMap<>();
        for (BlockPos p : computeContraptionRegion(player.level(), targetPos)) {
            BlockState state = player.level().getBlockState(p);
            if (!(state.getBlock() instanceof LiquidBlock)
                    && state.getPistonPushReaction() != PushReaction.DESTROY) {
                regionSnapshot.put(p.immutable(), state);
            }
        }

        // ── Silent rotated passes ────────────────────────────────────────────
        // Rotate the same contraption (positions AND directional block
        // properties, e.g. piston/observer FACING) around targetPos to compare
        // its behavior with the real-orientation run below. Nothing from the rotated passes is
        // ever rendered - a rotated position usually isn't even a real block
        // the player can see - so it must not call render(), and its debugger
        // side effects (PistonDebugger/ObserverDebugger/MovingBlockDebugger
        // "expected" logs) are wiped by the resetExpected() calls below before
        // the real pass runs, so nothing from this rotated pass leaks into
        // /slimestone compare.
        List<Map<BlockPos, Character>> rotatedClassifications = new ArrayList<>();
        List<Map<BlockPos, List<VirtualLevel.PistonActivation>>> rotatedPistonActivations = new ArrayList<>();
        List<Map<BlockPos, List<VirtualLevel.ObserverAction>>> rotatedObserverActions = new ArrayList<>();
        List<Rotation> rotationsToCheck = switch (rotationCheckMode) {
            case OFF -> List.of();
            case HALF_TURN -> List.of(Rotation.CLOCKWISE_180);
            case ALL -> List.of(Rotation.CLOCKWISE_90, Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90);
        };
        for (Rotation rotation : rotationsToCheck) {
            VirtualLevel rotatedLevel = simulateRotatedRegion(player, regionSnapshot, targetPos, rotation);
            rotatedClassifications.add(mapRotatedClassificationsToOriginal(
                    rotatedLevel.getMovementClassifications(), targetPos, rotation));
            rotatedPistonActivations.add(mapRotatedPistonsToOriginal(
                    rotatedLevel.getPistonActivations(), targetPos, rotation));
            rotatedObserverActions.add(mapRotatedPistonsToOriginal(
                    rotatedLevel.getObserverActions(), targetPos, rotation));
        }

        // ── Real-orientation pass ────────────────────────────────────────────
        ObserverDebugger.resetExpected();
        PistonDebugger.resetExpected();
        MovingBlockDebugger.resetExpected();

        VirtualLevel level = new VirtualLevel(player);
        lastSimLevel = level;

        for (Map.Entry<BlockPos, BlockState> e : regionSnapshot.entrySet()) {
            level.setBlockRaw(e.getKey(), e.getValue());
        }
        seedTrigger(level, targetPos);

        player.getServer().getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withSuppressedOutput(),
                "kill @e[type=minecraft:text_display]"
        );

        level.simulate(SIM_MAX_TICKS);

        Set<BlockPos> rotationActivationConflicts = new HashSet<>();
        Map<BlockPos, Set<Integer>> pistonOrderConflicts = new HashMap<>();
        Set<BlockPos> observerRotationConflicts = new HashSet<>();
        Map<BlockPos, Set<Integer>> observerOrderConflicts = new HashMap<>();
        if (rotationCheckMode != RotationCheckMode.OFF) {
            Map<BlockPos, List<VirtualLevel.PistonActivation>> originalActivations = level.getPistonActivations();
            for (Map.Entry<BlockPos, List<VirtualLevel.PistonActivation>> piston : originalActivations.entrySet()) {
                boolean fullConflict = false;
                for (Map<BlockPos, List<VirtualLevel.PistonActivation>> rotatedActivations : rotatedPistonActivations) {
                    List<VirtualLevel.PistonActivation> rotated = rotatedActivations.get(piston.getKey());
                    if (rotated == null || rotated.size() != piston.getValue().size()) {
                        fullConflict = true;
                        break;
                    }
                    for (int i = 0; i < rotated.size(); i++) {
                        VirtualLevel.PistonActivation originalAction = piston.getValue().get(i);
                        VirtualLevel.PistonActivation rotatedAction = rotated.get(i);
                        if (originalAction.tick() != rotatedAction.tick()
                                || originalAction.extending() != rotatedAction.extending()
                                || originalAction.blockCount() != rotatedAction.blockCount()) {
                            fullConflict = true;
                            break;
                        }
                        if (!originalAction.suffix().equals(rotatedAction.suffix())) {
                            pistonOrderConflicts.computeIfAbsent(piston.getKey(), p -> new HashSet<>())
                                    .add(originalAction.displayIndex());
                        }
                    }
                    if (fullConflict) break;
                }
                if (fullConflict) {
                    rotationActivationConflicts.add(piston.getKey());
                    pistonOrderConflicts.remove(piston.getKey());
                }
            }

            Map<BlockPos, List<VirtualLevel.ObserverAction>> originalObserverActions = level.getObserverActions();
            for (Map.Entry<BlockPos, List<VirtualLevel.ObserverAction>> observer : originalObserverActions.entrySet()) {
                boolean fullConflict = false;
                for (Map<BlockPos, List<VirtualLevel.ObserverAction>> rotatedActions : rotatedObserverActions) {
                    List<VirtualLevel.ObserverAction> rotated = rotatedActions.get(observer.getKey());
                    if (rotated == null || rotated.size() != observer.getValue().size()) {
                        fullConflict = true;
                        break;
                    }
                    for (int i = 0; i < rotated.size(); i++) {
                        VirtualLevel.ObserverAction originalAction = observer.getValue().get(i);
                        VirtualLevel.ObserverAction rotatedAction = rotated.get(i);
                        if (originalAction.tick() != rotatedAction.tick()
                                || originalAction.poweredOn() != rotatedAction.poweredOn()) {
                            fullConflict = true;
                            break;
                        }
                        if (!originalAction.suffix().equals(rotatedAction.suffix())) {
                            observerOrderConflicts.computeIfAbsent(observer.getKey(), p -> new HashSet<>()).add(i);
                        }
                    }
                    if (fullConflict) break;
                }
                if (fullConflict) {
                    observerRotationConflicts.add(observer.getKey());
                    observerOrderConflicts.remove(observer.getKey());
                }
            }
        }
        level.setRotationActivationConflicts(rotationActivationConflicts);
        level.setPistonOrderConflicts(pistonOrderConflicts);
        level.setObserverRotationConflicts(observerRotationConflicts);
        level.setObserverOrderConflicts(observerOrderConflicts);

        // Any origin whose stationary/moving classification differs in one or
        // more rotated runs gets an orange marker; all rotations are checked.
        Map<BlockPos, Character> realClassification = level.getMovementClassifications();
        Set<BlockPos> directionalConflicts = new HashSet<>();
        Set<BlockPos> allFlaggedOrigins = new HashSet<>(realClassification.keySet());
        for (Map<BlockPos, Character> rotatedClassification : rotatedClassifications) {
            allFlaggedOrigins.addAll(rotatedClassification.keySet());
        }
        for (BlockPos origin : allFlaggedOrigins) {
            for (Map<BlockPos, Character> rotatedClassification : rotatedClassifications) {
                if (!java.util.Objects.equals(realClassification.get(origin), rotatedClassification.get(origin))) {
                    directionalConflicts.add(origin);
                    break;
                }
            }
        }
        level.setDirectionalConflicts(directionalConflicts);

        level.render(poiModeActive);

        if (poiModeActive) {
            // A change came in while showing a windowed /poi view - keep
            // showing that same window against the fresh simulation instead
            // of falling back to the full end-of-run display.
            level.spawnPointOfInterest(lastPoiGt, lastPoiWindow);
        }

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

    private static VirtualLevel simulateRotatedRegion(
            ServerPlayer player,
            Map<BlockPos, BlockState> regionSnapshot,
            BlockPos pivot,
            Rotation rotation
    ) {
        Map<BlockPos, BlockState> rotatedRegion = VirtualLevel.rotateRegion(regionSnapshot, pivot, rotation);
        VirtualLevel rotatedLevel = new VirtualLevel(player);
        for (Map.Entry<BlockPos, BlockState> entry : rotatedRegion.entrySet()) {
            rotatedLevel.setBlockRaw(entry.getKey(), entry.getValue());
        }
        seedTrigger(rotatedLevel, pivot);
        rotatedLevel.simulate(SIM_MAX_TICKS);
        return rotatedLevel;
    }

    private static Map<BlockPos, Character> mapRotatedClassificationsToOriginal(
            Map<BlockPos, Character> rotatedClassifications,
            BlockPos pivot,
            Rotation rotation
    ) {
        Rotation inverse = switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case CLOCKWISE_180 -> Rotation.CLOCKWISE_180;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> Rotation.NONE;
        };

        Map<BlockPos, Character> originalPositions = new HashMap<>();
        for (Map.Entry<BlockPos, Character> entry : rotatedClassifications.entrySet()) {
            BlockPos originalPos = StructureTemplate.transform(entry.getKey(), Mirror.NONE, inverse, pivot);
            originalPositions.put(originalPos.immutable(), entry.getValue());
        }
        return originalPositions;
    }

    private static <T> Map<BlockPos, List<T>> mapRotatedPistonsToOriginal(
            Map<BlockPos, List<T>> rotatedPistons,
            BlockPos pivot,
            Rotation rotation
    ) {
        Rotation inverse = switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case CLOCKWISE_180 -> Rotation.CLOCKWISE_180;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> Rotation.NONE;
        };

        Map<BlockPos, List<T>> originalPositions = new HashMap<>();
        for (Map.Entry<BlockPos, List<T>> entry : rotatedPistons.entrySet()) {
            BlockPos rotatedPos = entry.getKey();
            BlockPos originalPos = StructureTemplate.transform(rotatedPos, Mirror.NONE, inverse, pivot);
            originalPositions.put(originalPos.immutable(), entry.getValue());
        }
        return originalPositions;
    }

    private BlockPos getTargetBlock(ServerPlayer player) {
        double distance = 50.0;
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getViewVector(1.0f);
        Vec3 endPos = eyePos.add(lookVec.x * distance, lookVec.y * distance, lookVec.z * distance);

        ClipContext ctx = new ClipContext(
                eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        BlockHitResult hit = player.level().clip(ctx);

        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private static boolean isRedstoneComponent(BlockState state) {
        return state.isSignalSource()
                || state.hasAnalogOutputSignal()
                || state.getBlock() instanceof PistonBaseBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof DoorBlock
                || state.is(Blocks.OBSERVER)
                || state.is(Blocks.REDSTONE_LAMP)
                || state.is(Blocks.DISPENSER)
                || state.is(Blocks.DROPPER)
                || state.is(Blocks.HOPPER)
                || state.is(Blocks.TNT);
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
