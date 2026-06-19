package com.pingu.slimestone.mixin;

import com.pingu.slimestone.PistonDebugger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PistonBaseBlock.class)
public class PistonBaseBlockMixin {

    @Inject(
            method = "checkIfExtend",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;blockEvent(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;II)V"
            )
    )
    private void onScheduleBlockEvent(Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        if (level instanceof ServerLevel serverLevel) {
            boolean isExtended = state.getValue(BlockStateProperties.EXTENDED);
            boolean willExtend = !isExtended;

            PistonDebugger.logReal(pos, willExtend, serverLevel.getGameTime());
        }
    }
}