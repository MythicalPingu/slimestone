package com.pingu;

import com.pingu.command.PistonSimCommand;
import com.pingu.util.NetworkRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Slimestone implements ModInitializer {
    public static final String MOD_ID = "slimestone";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        NetworkRegistry.registerServer();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            PistonSimCommand.register(dispatcher)
        );
        LOGGER.info("Slimestone initialized.");
    }
}
