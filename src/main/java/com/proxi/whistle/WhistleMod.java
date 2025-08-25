package com.proxi.whistle;

import com.proxi.whistle.component.ModDataComponents;
import com.proxi.whistle.component.BoundHorseData;
import com.proxi.whistle.item.WhistleItem;
import com.proxi.whistle.util.ItemStackNbtUtil;
import com.proxi.whistle.world.BoundEntityStorage;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import java.util.UUID;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NbtCompound;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import com.proxi.whistle.world.BoundEntityStorage;


public class WhistleMod implements ModInitializer {
    public static final String MOD_ID = "whistle";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final RegistryKey<Item> WHISTLE_KEY = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, "whistle"));
    public static Item WHISTLE;

    @Override
    public void onInitialize() {
        // Initialize custom data components first
        ModDataComponents.initialize();

        // Initialize BoundEntityStorage
        BoundEntityStorage.init();
		
        // load persisted state from world save on server start
        ServerLifecycleEvents.SERVER_STARTED.register((MinecraftServer server) -> {
            try {
                BoundEntityStorage.initPersistence(server);
                LOGGER.info("Whistle: BoundEntityStorage persistence initialized");
            } catch (Throwable t) {
                LOGGER.warn("Whistle: Failed to initialize BoundEntityStorage persistence", t);
            }
        });

        // flush state to disk on server stopping (ensures last-minute writes)
        ServerLifecycleEvents.SERVER_STOPPING.register((MinecraftServer server) -> {
            try {
                BoundEntityStorage.flushToDisk();
                LOGGER.info("Whistle: BoundEntityStorage flushed to disk");
            } catch (Throwable t) {
                LOGGER.warn("Whistle: Failed to flush BoundEntityStorage to disk", t);
            }
        });

        // Create item settings with registry key
        Item.Settings settings = new Item.Settings().maxCount(1).registryKey(WHISTLE_KEY);

        // Create and register the item
        WHISTLE = Registry.register(
            Registries.ITEM,
            WHISTLE_KEY,
            new WhistleItem(settings)
        );

        // Add to creative tab
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(content -> {
            content.add(WHISTLE);
        });

        // Re-add UseEntityCallback so sneaking-right-click with the whistle binds
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            // Only main hand
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;

            ItemStack stack = player.getStackInHand(hand);
            if (!(stack.getItem() instanceof WhistleItem)) return ActionResult.PASS;

            // Only bind horses (and subclasses)
            if (!(entity instanceof AbstractHorseEntity horseEntity)) return ActionResult.PASS;

            // Only bind when sneaking
            if (!player.isSneaking()) return ActionResult.PASS;

            // Server-side binding logic
            if (!world.isClient) {
                UUID uuid = entity.getUuid();
                Identifier dimensionId = entity.getWorld().getRegistryKey().getValue();
                BlockPos pos = entity.getBlockPos();

                // write the persistent component (server-side)
                stack.set(ModDataComponents.BOUND_HORSE_DATA, new BoundHorseData(uuid, dimensionId, pos));

                // Immediately snapshot the entity server-side so we can recreate it immediately
                BoundEntityStorage.updateSnapshotFromEntity(entity);
                BoundEntityStorage.storeSnapshot(uuid, BoundEntityStorage.getSnapshotNbt(uuid) != null ? BoundEntityStorage.getSnapshotNbt(uuid) : new NbtCompound(), dimensionId, pos);

                // Also write a small client-visible NBT block on the ItemStack so the tooltip updates immediately
                NbtCompound root = ItemStackNbtUtil.getOrCreateNbt(stack);
                NbtCompound bh = new NbtCompound();
                bh.putUuid("uuid", uuid);
                bh.putString("dimension", dimensionId.toString());
                bh.putInt("x", pos.getX());
                bh.putInt("y", pos.getY());
                bh.putInt("z", pos.getZ());
                root.put("WhistleBoundHorse", bh);
                // Make sure the stack actually stores it (some mappings need explicit setter)
                ItemStackNbtUtil.setNbt(stack, root);

                player.sendMessage(Text.translatable("item.whistle.whistle.bound"), true);
                world.playSound(null, player.getBlockPos(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1.0f, 1.0f);

                // Prevent vanilla from opening the horse inventory — indicate we've handled it
                return ActionResult.SUCCESS;
            }

            // Client: return SUCCESS as well so the client doesn't open the UI
            return ActionResult.SUCCESS;
        });

        // Register entity lifecycle events
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            // Only care about living entities (horses/pets)
            if (entity instanceof AbstractHorseEntity) {
                // store snapshot when it unloads
                BoundEntityStorage.onEntityUnload(entity, world);
            }
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            BoundEntityStorage.onEntityLoad(entity, world);
        });

        // Death event for living entities
        ServerLivingEntityEvents.AFTER_DEATH.register((livingEntity, damageSource) -> {
            if (livingEntity instanceof AbstractHorseEntity) {
                BoundEntityStorage.markDead(livingEntity.getUuid());
            }
        });

        // Player disconnect: check if they logged out while riding a bound entity
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.player;
            if (player == null) return;
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof AbstractHorseEntity horse) {
                UUID horseId = horse.getUuid();
                if (BoundEntityStorage.isBound(horseId)) {
                    BoundEntityStorage.markWithOfflinePlayer(horseId, player.getName().getString());
                }
            }
        });

        // Server tick: once per second refresh loaded entity snapshots
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                if (server.getTicks() % 20 == 0) {
                    BoundEntityStorage.tick(server);
                }
            } catch (Throwable t) {
                LOGGER.warn("BoundEntityStorage tick failed: ", t);
            }
        });

        LOGGER.info("Horse Whistle Mod initialized!");
        LOGGER.info("Registered item: " + Registries.ITEM.getId(WHISTLE));
    }
}
