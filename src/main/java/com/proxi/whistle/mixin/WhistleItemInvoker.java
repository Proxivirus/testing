package com.proxi.whistle.mixin;

import com.proxi.whistle.item.WhistleItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.UUID;

/**
 * Invoker for WhistleItem.writeBindingNbt(...) so we can call the
 * private static method without changing WhistleItem.java.
 */
@Mixin(WhistleItem.class)
public interface WhistleItemInvoker {
    @Invoker("writeBindingNbt")
    static void invokeWriteBindingNbt(ItemStack stack, UUID uuid, Identifier dimension, BlockPos pos) {
        throw new AssertionError("Mixin failed to apply: WhistleItemInvoker.invokeWriteBindingNbt");
    }
}
