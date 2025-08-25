package com.proxi.whistle;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import com.proxi.whistle.item.WhistleItem;
import com.proxi.whistle.world.BoundEntityStorage;

public class WhistleClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Relink whistles when player joins a world
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            relinkWhistles(client);
        });
    }

    private void relinkWhistles(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        for (ItemStack stack : player.getInventory().main) {
            if (stack.getItem() instanceof WhistleItem) {
                WhistleItem.tryRelink(stack, client.world);
            }
        }

        for (ItemStack stack : player.getInventory().offHand) {
            if (stack.getItem() instanceof WhistleItem) {
                WhistleItem.tryRelink(stack, client.world);
            }
        }
    }
}
