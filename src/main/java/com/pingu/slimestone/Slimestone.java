package com.pingu.slimestone;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;

public class Slimestone implements ModInitializer {

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
}