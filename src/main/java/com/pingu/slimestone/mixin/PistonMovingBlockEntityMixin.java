package com.pingu.slimestone.mixin;

import com.pingu.slimestone.MovingBlockDebugger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityMixin {

    @Shadow public abstract BlockState getMovedState();
    @Shadow public abstract boolean isExtending();

    @Inject(method = "setLevel", at = @At("RETURN"))
    private void onSetLevel(Level level, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            PistonMovingBlockEntity be = (PistonMovingBlockEntity) (Object) this;
            BlockPos pos = be.getBlockPos();
            BlockState movingState = this.getMovedState();
            boolean extending = this.isExtending();

            // Log the real-world creation of the moving block entity
            MovingBlockDebugger.logReal(pos, movingState, extending, serverLevel.getGameTime());
        }
    }
}