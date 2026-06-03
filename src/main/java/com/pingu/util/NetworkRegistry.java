package com.pingu.util;

import com.pingu.simulator.PistonEvent;
import com.pingu.simulator.SimulationManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.chat.Component; // <--- ADD THIS
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class NetworkRegistry {

    public static void registerServer() {
        PayloadTypeRegistry.playS2C().register(SimResultPacket.TYPE, SimResultPacket.CODEC);
    }

    public static void registerClient() {
        ClientPlayNetworking.registerGlobalReceiver(SimResultPacket.TYPE, (payload, ctx) -> {
            ctx.client().execute(() -> {
                var player = ctx.client().player;
                if (player == null) return;

                // Use displayClientMessage for LocalPlayer
                player.displayClientMessage(Component.literal("§6══ Piston Simulation Results ══"), false);

                for (SimResultPacket.EventEntry entry : payload.entries()) {
                    String status = switch (entry.result()) {
                        case SUCCESS_PULLED        -> "§a✓ Pulled";
                        case SUCCESS_NO_PULL       -> "§a✓ Retracted";
                        case SUCCESS_PUSHED        -> "§a✓ Pushed";
                        case FAILED_BLOCKED        -> "§c✗ Blocked";
                        case FAILED_PUSH_BLOCKED   -> "§c✗ Push Blocked";
                        case FAILED_PUSH_NO_PISTON -> "§7✗ No Piston";
                    };

                    player.displayClientMessage(Component.literal(
                            "§7[gt" + entry.tick() + "] " + status + " at " + entry.pos().toShortString()
                    ), false);

                    for (String change : entry.changes()) {
                        player.displayClientMessage(Component.literal("§8  - " + change), false);
                    }
                }
            });
        });
    }
}