package com.pingu.slimestone.mixin;

import com.pingu.slimestone.Slimestone;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public class BlockItemMixin {

    @Inject(method = "place", at = @At("RETURN"))
    private void slimestone$afterPlace(BlockPlaceContext context,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        if (context.getLevel().isClientSide()) {
            return;
        }

        if (cir.getReturnValue().consumesAction()) {
            Slimestone.onWorldChanged();
        }
    }
}