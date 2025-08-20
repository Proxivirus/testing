package com.proxi.whistle;

import com.proxi.whistle.item.WhistleItem;
import com.proxi.whistle.component.ModDataComponents;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.Identifier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.ActionResult;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import com.proxi.whistle.component.BoundHorseData;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.item.ItemStack;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.util.Hand;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.server.network.ServerPlayerEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core mod class.
 */
public class WhistleMod implements ModInitializer {
    public static final String MOD_ID = "whistlemod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final RegistryKey<Item> WHISTLE_KEY = RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, "whistle"));
    public static Item WHISTLE;

    // Ticket type for forced chunk loads. Initialize in onInitialize().
    public static ChunkTicketType<UUID> WHISTLE_TICKET;

    // Retry jobs map (UUID -> job)
    static final Map<UUID, WhistleRetryJob> RETRY_JOBS = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing WhistleMod");

        // initialize components (do this at runtime)
        try {
            ModDataComponents.initialize();
        } catch (Throwable t) {
            LOGGER.warn("ModDataComponents.initialize() failed or missing: {}", t.getMessage());
        }

        // create ticket type at runtime
        WHISTLE_TICKET = ChunkTicketType.create(MOD_ID + ":whistle_ticket", Comparator.comparing(UUID::toString));

        // safe item registration (skip if already registered elsewhere)
        try {
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
        } catch (Exception e) {
            LOGGER.info("Whistle item registration skipped or already registered: {}", e.getMessage());
        }

        // server tick handler for retry jobs
        ServerTickEvents.START_SERVER_TICK.register(this::onServerTick);

        LOGGER.info("WhistleMod initialized (ticket={}, item={})", WHISTLE_TICKET, WHISTLE);
		
        // Register the entity interaction event
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            ItemStack stack = player.getStackInHand(hand);
            if (stack.getItem() instanceof WhistleItem && player.isSneaking() && entity instanceof AbstractHorseEntity) {
                if (!world.isClient) {
                    UUID uuid = entity.getUuid();
                    Identifier dimensionId = entity.getWorld().getRegistryKey().getValue();
                    BlockPos pos = entity.getBlockPos();
                    
                    // Get horse name and owner name
                    String horseName = entity.getCustomName() != null ? 
                        entity.getCustomName().getString() : 
                        Text.translatable("entity.minecraft.horse").getString();
                        
                    String ownerName = "Wild";
                    if (entity instanceof TameableEntity tameable && tameable.getOwner() != null) {
                        if (tameable.getOwner() instanceof ServerPlayerEntity ownerPlayer) {
                            ownerName = ownerPlayer.getGameProfile().getName() + "'s Horse";
                        }
                    }
                    
                    stack.set(ModDataComponents.BOUND_HORSE_DATA, new BoundHorseData(uuid, dimensionId, pos, horseName, ownerName));
                    player.sendMessage(Text.translatable("item.whistlemod.whistle.bound"), true);
                    world.playSound(null, player.getBlockPos(), SoundEvents.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1.0f, 1.0f);
                }
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
    }

    private void onServerTick(MinecraftServer server) {
        if (RETRY_JOBS.isEmpty()) return;

        for (UUID horseUuid : RETRY_JOBS.keySet().toArray(new UUID[0])) {
            WhistleRetryJob job = RETRY_JOBS.get(horseUuid);
            if (job == null) continue;

            try {
                job.tick(server);
                if (job.isDone()) RETRY_JOBS.remove(horseUuid);
            } catch (Throwable t) {
                LOGGER.error("[WhistleMod] Error while processing retry job for UUID={} : {}", horseUuid, t.getMessage(), t);
                RETRY_JOBS.remove(horseUuid);
            }
        }
    }

    public static void scheduleRetry(WhistleRetryJob job) {
        RETRY_JOBS.put(job.horseUuid, job);
    }

    public static final class WhistleRetryJob {
        final UUID horseUuid;
        final java.util.UUID playerUuid;
        final net.minecraft.util.math.ChunkPos chunkPos;
        final net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey;
        final int maxAttempts;
        int attemptsLeft;
        public boolean done = false;

        public WhistleRetryJob(UUID horseUuid,
                               UUID playerUuid,
                               net.minecraft.registry.RegistryKey<net.minecraft.world.World> worldKey,
                               net.minecraft.util.math.ChunkPos chunkPos,
                               int maxAttempts) {
            this.horseUuid = horseUuid;
            this.playerUuid = playerUuid;
            this.worldKey = worldKey;
            this.chunkPos = chunkPos;
            this.maxAttempts = maxAttempts;
            this.attemptsLeft = maxAttempts;
        }

        public void tick(MinecraftServer server) {
            if (attemptsLeft <= 0) {
                WhistleMod.LOGGER.warn("[WhistleMod] RetryJob out of attempts for UUID={}", horseUuid);
                cleanup(server);
                done = true;
                return;
            }

            attemptsLeft--;

            net.minecraft.server.world.ServerWorld targetWorld = server.getWorld(worldKey);
            if (targetWorld == null) {
                WhistleMod.LOGGER.warn("[WhistleMod] RetryJob target world missing for UUID={} world={}", horseUuid, worldKey);
                cleanup(server);
                done = true;
                return;
            }

            net.minecraft.entity.Entity e = targetWorld.getEntity(horseUuid);
            if (e instanceof net.minecraft.entity.passive.AbstractHorseEntity horse) {
                WhistleMod.LOGGER.info("[WhistleMod] RetryJob found horse UUID={} in world {} (attemptsLeft={})", horseUuid, worldKey, attemptsLeft);
                net.minecraft.server.network.ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUuid);
                if (player != null) {
                    com.proxi.whistle.item.WhistleItem.handleFoundHorseOnRetry(horse, targetWorld, player);
                } else {
                    WhistleMod.LOGGER.warn("[WhistleMod] Cannot notify/teleport because player {} is offline", playerUuid);
                }
                cleanup(server);
                done = true;
                return;
            } else {
                WhistleMod.LOGGER.info("[WhistleMod] RetryJob: horse UUID={} not present yet (attemptsLeft={})", horseUuid, attemptsLeft);
            }
        }

        private void cleanup(MinecraftServer server) {
            try {
                net.minecraft.server.world.ServerWorld targetWorld = server.getWorld(worldKey);
                if (targetWorld != null) {
                    // call removeTicket with the same argument we used to add the ticket
                    targetWorld.getChunkManager().removeTicket(WHISTLE_TICKET, chunkPos, 31, playerUuid);
                    WhistleMod.LOGGER.info("[WhistleMod] removed ticket for chunk {} (cleanup)", chunkPos);
                }
            } catch (Throwable t) {
                WhistleMod.LOGGER.warn("[WhistleMod] Failed to remove ticket during cleanup: {}", t.getMessage());
            }
        }

        public boolean isDone() {
            return done;
        }
    }
}
