package com.proxi.whistle;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.item.ItemStack;
import com.proxi.whistle.item.WhistleItem;
import net.minecraft.util.math.BlockPos;
import com.proxi.whistle.mixin.WhistleItemInvoker;
import com.proxi.whistle.network.HorseSyncPayload;
import com.proxi.whistle.network.HorseSyncPayload;

import java.util.UUID;

public class WhistleClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register the handler for the HorseSyncPayload type
		ClientPlayNetworking.registerGlobalReceiver(com.proxi.whistle.network.HorseSyncPayload.ID, (payload, context) -> {
			MinecraftClient client = context.client();
			client.execute(() -> {
				try {
					UUID payloadUuid = payload.uuid();
					net.minecraft.util.Identifier dim = payload.dimension();
					net.minecraft.util.math.BlockPos pos = payload.pos();

					System.out.println("[Whistle] Client received sync for uuid " + payloadUuid);

					if (client.player == null) {
						System.out.println("[Whistle] no client.player present");
						return;
					}

					boolean foundAny = false;

					for (int i = 0; i < client.player.getInventory().size(); i++) {
						ItemStack stack = client.player.getInventory().getStack(i);
						if (stack == null || stack.isEmpty()) continue;
						if (!(stack.getItem() instanceof com.proxi.whistle.item.WhistleItem)) continue;

						boolean matches = false;
						com.proxi.whistle.component.BoundHorseData comp = stack.get(com.proxi.whistle.component.ModDataComponents.BOUND_HORSE_DATA);
						if (comp != null && payloadUuid.equals(comp.uuid())) {
							matches = true;
						} else {
							net.minecraft.nbt.NbtCompound root = com.proxi.whistle.util.ItemStackNbtUtil.getNbt(stack);
							if (root != null && root.contains("WhistleBoundHorse") && root.get("WhistleBoundHorse") instanceof net.minecraft.nbt.NbtCompound) {
								net.minecraft.nbt.NbtCompound bh = root.getCompound("WhistleBoundHorse");
								UUID maybe = com.proxi.whistle.item.WhistleItem.readBoundUuid(bh);
								if (maybe != null && maybe.equals(payloadUuid)) matches = true;
							}
						}

						if (matches) {
							foundAny = true;
							System.out.println("[Whistle] updating client stack in slot " + i + " for uuid " + payloadUuid);

							// 1) Update client-visible NBT (existing)
							com.proxi.whistle.mixin.WhistleItemInvoker.invokeWriteBindingNbt(stack, payloadUuid, dim, pos);

							// 2) ALSO update the client-side component so appendTooltip() prefers fresh data
							//    (use fully-qualified names to avoid import issues)
							stack.set(com.proxi.whistle.component.ModDataComponents.BOUND_HORSE_DATA,
									  new com.proxi.whistle.component.BoundHorseData(payloadUuid, dim, pos));

							// 3) Write back into the inventory so Minecraft re-renders the slot/tooltip
							client.player.getInventory().setStack(i, stack);
						}
					}

					if (!foundAny) {
						System.out.println("[Whistle] received sync for uuid " + payloadUuid + " but found no matching whistle in inventory");
					}
				} catch (Throwable t) {
					System.out.println("[Whistle] client handler threw: " + t);
				}
			});
		});
    }
}
