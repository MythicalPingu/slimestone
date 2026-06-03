package com.pingu;

import com.pingu.util.NetworkRegistry;
import net.fabricmc.api.ClientModInitializer;

// SlimestoneClient.java
public class SlimestoneClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        NetworkRegistry.registerClient();
        // REMOVE THIS LINE:
        // WorldRenderEvents.LAST.register(SimResultRendererClient::render);
    }
}