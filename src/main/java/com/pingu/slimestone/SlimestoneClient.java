package com.pingu.slimestone;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class SlimestoneClient implements ClientModInitializer {
    private static final KeyMapping CYCLE_STARTING_INPUT = KeyBindingHelper.registerKeyBinding(
            new KeyMapping(
                    "key.slimestone.cycle_starting_input",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_F14,
                    "key.categories.slimestone"
            )
    );

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(SlimestoneClient::onEndClientTick);
    }

    private static void onEndClientTick(Minecraft client) {
        while (CYCLE_STARTING_INPUT.consumeClick()) {
            if (client.screen == null && client.player != null && client.getConnection() != null) {
                client.getConnection().sendCommand("slimestone cycle");
            }
        }
    }
}
