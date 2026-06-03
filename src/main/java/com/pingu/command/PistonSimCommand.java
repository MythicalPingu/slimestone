package com.pingu.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.pingu.simulator.PistonEvent;
import com.pingu.simulator.SimulationManager;
import com.pingu.util.SimResultPacket;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * /pistonsim — piston retraction chain validator
 *
 * Subcommands:
 *   /pistonsim run <x> <y> <z>          — simulate the piston at the given position (must be extended)
 *   /pistonsim run                       — simulate the piston you're looking at
 *   /pistonsim clear                     — hide overlays
 *   /pistonsim status                    — show result summary in chat
 */
public class PistonSimCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("pistonsim")
                        .requires(src -> src.hasPermission(2))

                        // /pistonsim run <x y z>
                        .then(Commands.literal("run")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> runAt(ctx, BlockPosArgument.getLoadedBlockPos(ctx, "pos")))
                                )
                                // /pistonsim run (no args — uses targeted block)
                                .executes(PistonSimCommand::runAtLook)
                        )

                        // /pistonsim clear
                        .then(Commands.literal("clear")
                                .executes(PistonSimCommand::clearOverlay)
                        )

                        // /pistonsim status
                        .then(Commands.literal("status")
                                .executes(PistonSimCommand::showStatus)
                        )
        );
    }

    // -------------------------------------------------------------------------

    // Replace the runAt method:
    private static int runAt(CommandContext<CommandSourceStack> ctx, BlockPos pos) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) return 0;

        BlockState state = player.level().getBlockState(pos);
        if (!state.is(Blocks.PISTON) && !state.is(Blocks.STICKY_PISTON)) {
            src.sendFailure(Component.literal("No piston at " + formatPos(pos) + "."));
            return 0;
        }

        // NEW: Enforce un-extended pistons
        if (state.getValue(PistonBaseBlock.EXTENDED)) {
            src.sendFailure(Component.literal(
                    "Piston at " + formatPos(pos) + " is extended. Simulate un-extended sticky pistons to test pulling."));
            return 0;
        }

        Direction facing = state.getValue(PistonBaseBlock.FACING);
        src.sendSuccess(() -> Component.literal(
                "§aStarting simulation on un-extended piston at §f" + formatPos(pos) + "…"), false);

        startAndAwaitResult(player, pos, facing);
        return 1;
    }

    private static int runAtLook(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("Must be run by a player."));
            return 0;
        }

        // Use the block the player is looking at
        var hitResult = player.pick(6.0, 1.0f, false);
        if (hitResult == null || !(hitResult instanceof net.minecraft.world.phys.BlockHitResult bhr)) {
            src.sendFailure(Component.literal("Not looking at a block."));
            return 0;
        }

        return runAt(ctx, bhr.getBlockPos());
    }

    private static int clearOverlay(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) return 0;

        SimulationManager.get().clearResult(player.getUUID());
        sendClearPacket(player);
        src.sendSuccess(() -> Component.literal("§7Simulation overlay cleared."), false);
        return 1;
    }

    private static int showStatus(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) return 0;

        if (SimulationManager.get().isRunning(player.getUUID())) {
            src.sendSuccess(() -> Component.literal("§eSim still running…"), false);
            return 1;
        }

        SimulationManager.SimulationResult result = SimulationManager.get().getResult(player.getUUID());
        if (result == null) {
            src.sendSuccess(() -> Component.literal("§7No simulation result. Run §f/pistonsim run §7first."), false);
            return 1;
        }

        printResultSummary(src, result);
        return 1;
    }

    // -------------------------------------------------------------------------

    /**
     * Starts the simulation off-thread and, when done, sends the result packet to the player.
     * Uses a small polling thread so we can send results back — Fabric has no built-in
     * CompletableFuture-to-server-thread bridge we can use from a command.
     */
    private static void startAndAwaitResult(ServerPlayer player, BlockPos pos, Direction facing) {
        SimulationManager.get().startSimulation(player, pos, facing);

        Thread watcher = new Thread(() -> {
            // Poll until the sim finishes (the executor handles the work, we just wait)
            int maxWait = 30_000; // 30s hard timeout
            int elapsed = 0;
            while (SimulationManager.get().isRunning(player.getUUID()) && elapsed < maxWait) {
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
                elapsed += 50;
            }

            SimulationManager.SimulationResult result = SimulationManager.get().getResult(player.getUUID());
            if (result == null) return;

            // Schedule sending back on the server thread
            player.server.execute(() -> {
                sendResultToClient(player, result);
                printResultSummary(player.createCommandSourceStack(), result);
            });
        }, "PistonSim-Watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void sendResultToClient(ServerPlayer player, SimulationManager.SimulationResult result) {
        List<SimResultPacket.EventEntry> entries = new ArrayList<>();
        for (PistonEvent event : result.events()) {
            SimResultPacket.Result r = switch (event.getResult()) {
                case SUCCESS_PULLED        -> SimResultPacket.Result.SUCCESS_PULLED;
                case SUCCESS_NO_PULL       -> SimResultPacket.Result.SUCCESS_NO_PULL;
                case SUCCESS_PUSHED        -> SimResultPacket.Result.SUCCESS_PUSHED;
                case FAILED_BLOCKED        -> SimResultPacket.Result.FAILED_BLOCKED;
            };
            entries.add(new SimResultPacket.EventEntry(event.getPistonPos(), event.getFacing(), r, event.getGameTick(), event.getChanges()));
        }
        ServerPlayNetworking.send(player, new SimResultPacket(entries));
    }

    private static void sendClearPacket(ServerPlayer player) {
        ServerPlayNetworking.send(player, new SimResultPacket(List.of()));
    }

    private static void printResultSummary(CommandSourceStack src, SimulationManager.SimulationResult result) {
        src.sendSuccess(() -> Component.literal("§6══ Piston Sim Result (" + result.elapsedMs() + "ms) ══"), false);

        for (PistonEvent event : result.events()) {
            String prefix = switch (event.getResult()) {
                case SUCCESS_PULLED        -> "§a✓ [PULL] ";
                case SUCCESS_NO_PULL       -> "§a✓ [RETRACT] ";
                case SUCCESS_PUSHED        -> "§a✓ [PUSH] ";
                case FAILED_BLOCKED        -> "§c✗ [BLOCKED] ";
            };
            String title = prefix + "§fPiston at " + formatPos(event.getPistonPos()) + " §7(gt" + event.getGameTick() + ")";

            src.sendSuccess(() -> Component.literal(title), false);

            for (String change : event.getChanges()) {
                src.sendSuccess(() -> Component.literal("§8  - " + change), false);
            }
        }
    }

    private static String formatPos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }
}