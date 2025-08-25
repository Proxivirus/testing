package com.proxi.whistle.mixin;

import com.proxi.whistle.component.BoundHorseData;
import com.proxi.whistle.component.ModDataComponents;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerInventory.class)
public class PlayerInventoryMixin {

    @Inject(method = "setStack", at = @At("TAIL"))
    private void onSetStack(int slot, ItemStack stack, CallbackInfo ci) {
        BoundHorseData data = stack.get(ModDataComponents.BOUND_HORSE_DATA);
        if (data != null) {
            // Call the private method through our @Invoker
            WhistleItemInvoker.invokeWriteBindingNbt(stack, data.uuid(), data.dimension(), data.pos());
        }
    }
}
