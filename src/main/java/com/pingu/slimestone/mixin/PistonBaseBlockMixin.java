package com.pingu.slimestone.mixin;

import com.pingu.slimestone.PistonDebugger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PistonBaseBlock.class)
public class PistonBaseBlockMixin {

    @Inject(
            method = "triggerEvent",
            at = @At("RETURN")
    )
    private void onTriggerEvent(BlockState state, Level level, BlockPos pos, int id, int param, CallbackInfoReturnable<Boolean> cir) {
        // cir.getReturnValueZ() is true ONLY if the piston successfully pushed/pulled
        if (level instanceof ServerLevel serverLevel && cir.getReturnValueZ()) {

            // id 0 = extend, id 1/2 = retract
            boolean isExtending = (id == 0);

            PistonDebugger.logReal(pos, isExtending, serverLevel.getGameTime());
        }
    }
}