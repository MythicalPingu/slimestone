package com.pingu.slimestone.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ScheduledTickAccess;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ObserverBlock.class)
public class ObserverBlockMixin {

    @Unique
    private static Long startTick = null;

    @Unique
    private void log(Level level, String message) {
        if (level instanceof ServerLevel serverLevel) {
            long currentTick = serverLevel.getGameTime();
            if (startTick == null) startTick = currentTick;
            long gt = currentTick - startTick;

            // Format: [GT X] Message
            String formatted = "§7[GT " + gt + "] " + message;
            serverLevel.getServer().getPlayerList().broadcastSystemMessage(Component.literal(formatted), false);
        }
    }

    // 1. Tick Logic: Turning ON/OFF
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
        boolean wasPowered = state.getValue(BlockStateProperties.POWERED);

        if (wasPowered) {
            log(level, "§3[Observer] " + pos.toShortString() + " → POWERED=false (pulse end)");
        } else {
            log(level, "§3[Observer] " + pos.toShortString() + " → POWERED=true (pulse start)");
        }
    }

    // 2. Schedule Logic: When the observer schedules its fire
    @Inject(method = "startSignal", at = @At("HEAD"))
    private void onStartSignal(LevelReader level, ScheduledTickAccess ticks, BlockPos pos, CallbackInfo ci) {
        if (level instanceof Level lvl) {
            log(lvl, "§3[Schedule] Observer at " + pos.toShortString() + " → fires next tick");
        }
    }

    // 3. Shape Update Logic
// 4. Trigger: Receiving shape update (Gold)
    @Inject(method = "updateShape", at = @At("HEAD"))
    private void onUpdateShape(BlockState state, LevelReader level, ScheduledTickAccess tickAccess,
                               BlockPos pos, Direction direction, BlockPos neighborPos,
                               BlockState neighborState, RandomSource random, CallbackInfoReturnable<BlockState> cir) {

        if (level instanceof Level lvl) {
            // 'direction' here is the direction FROM the observer TO the neighbor
            // 'direction' is the parameter you asked about!
            String side = direction.getName();

            // This logs which side of the observer received the update
            log(lvl, "§6[ShapeUpdate] Detected update from " + side + " side at " + neighborPos.toShortString());

            // If you want to know if it's the front:
            // if (direction == state.getValue(ObserverBlock.FACING)) { ... }
        }
    }
}