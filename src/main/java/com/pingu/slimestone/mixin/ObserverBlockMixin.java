package com.pingu.slimestone.mixin;

import com.pingu.slimestone.ObserverDebugger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ObserverBlock.class)
public class ObserverBlockMixin {

    /**
     * Fires at the HEAD of ObserverBlock.tick(), i.e. before the state actually
     * flips. The observer is about to transition to !wasPowered, so we report
     * that as the "new" value to match VirtualLevel's convention.
     */
    @Inject(method = "tick", at = @At("HEAD"))
    private void onTick(BlockState state, ServerLevel level, BlockPos pos,
                        RandomSource random, CallbackInfo ci) {

        boolean wasPowered = state.getValue(BlockStateProperties.POWERED);
        // wasPowered = true  → observer is turning OFF (new state = false)
        // wasPowered = false → observer is turning ON  (new state = true)
        ObserverDebugger.logReal(pos, !wasPowered, level.getGameTime());
    }
}