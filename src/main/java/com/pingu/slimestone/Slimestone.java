package com.pingu.slimestone;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public class Slimestone implements ModInitializer {

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("slimestone")
                    // 1. Path without arguments: relies on the player's line of sight
                    .executes(context -> {
                        ServerPlayer player = context.getSource().getPlayerOrException();
                        BlockPos target = getTargetBlock(player);

                        if (target == null) {
                            context.getSource().sendFailure(Component.literal("You must be looking at a block, or specify coordinates!"));
                            return 0;
                        }

                        runSimulation(player, target);
                        return 1;
                    })
                    // 2. Path with explicit coordinate arguments
                    .then(Commands.argument("pos", BlockPosArgument.blockPos())
                            .executes(context -> {
                                ServerPlayer player = context.getSource().getPlayerOrException();
                                BlockPos target = BlockPosArgument.getLoadedBlockPos(context, "pos");

                                runSimulation(player, target);
                                return 1;
                            })));
        });
    }

    /**
     * Performs a server-side raycast to find the block the player is currently looking at.
     */
    private BlockPos getTargetBlock(ServerPlayer player) {
        double distance = 10.0; // Reach distance limit
        Vec3 eyePos = player.getEyePosition();
        Vec3 lookVec = player.getViewVector(1.0f);
        Vec3 endPos = eyePos.add(lookVec.x * distance, lookVec.y * distance, lookVec.z * distance);

        ClipContext context = new ClipContext(eyePos, endPos, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        BlockHitResult hitResult = player.level().clip(context);

        if (hitResult.getType() == HitResult.Type.BLOCK) {
            return hitResult.getBlockPos();
        }
        return null;
    }

    private void runSimulation(ServerPlayer player, BlockPos targetPos) {
        player.sendSystemMessage(Component.literal("§a[Slimestone] Starting Virtual Simulation at " + targetPos.toShortString()));
        VirtualLevel level = new VirtualLevel(player);
        int r = 50;

        // Clone reality into the virtual level
        for (int x = -r; x <= r; x++) {
            for (int y = -r; y <= r; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos p = targetPos.offset(x, y, z);
                    level.setBlockRaw(p, player.level().getBlockState(p));
                }
            }
        }

        BlockState targetState = level.getBlockState(targetPos);

        // Evaluate starting block
        if (targetState.getBlock() instanceof PistonBaseBlock) {
            level.log("Simulation Initialized. Checking initial power for piston at " + targetPos.toShortString());
            level.checkIfExtend(targetPos, targetState);
        }
        else if (targetState.is(Blocks.OBSERVER)) {
            level.log("Simulation Initialized. Observer detected an artificial update at " + targetPos.toShortString());
            // Pretend the observer saw a block update by manually queueing the 2-tick pulse delay
            level.scheduleTick(targetPos, Blocks.OBSERVER, 2);
        }
        else {
            player.sendSystemMessage(Component.literal("§cTarget is not a piston or an observer!"));
            return;
        }

        level.runTickLoop(1000);
    }
}