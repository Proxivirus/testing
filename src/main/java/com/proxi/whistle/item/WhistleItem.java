package com.proxi.whistle.item;

import com.proxi.whistle.WhistleMod;
import com.proxi.whistle.component.BoundHorseData;
import com.proxi.whistle.component.ModDataComponents;
import com.proxi.whistle.world.summoning.WhistleSummoningStorage;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Whistle item: summons a bound horse.
 * Uses WhistleMod.WHISTLE_TICKET + a scheduled retry system (server ticks) to reliably load entities.
 */
public class WhistleItem extends Item {
    private static final Logger LOG = WhistleMod.LOGGER;
    private static final int MAX_RETRY_TICKS = 6; // number of server ticks to retry

    public WhistleItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult use(World world, net.minecraft.entity.player.PlayerEntity user, Hand hand) {
        if (world.isClient) return ActionResult.SUCCESS;

        ServerWorld requestor = (ServerWorld) world;
        ServerPlayerEntity player = (ServerPlayerEntity) user;
        ItemStack stack = player.getStackInHand(hand);

        BoundHorseData bound = stack.get(ModDataComponents.BOUND_HORSE_DATA);
        if (bound == null) {
            player.sendMessage(Text.translatable("item.whistle.whistle.unbound"), true);
            return ActionResult.SUCCESS;
        }

        UUID horseUuid = bound.uuid();
        RegistryKey<World> storedKey = RegistryKey.of(RegistryKeys.WORLD, bound.dimension());
        BlockPos storedPos = bound.pos();

        LOG.info("[USE] Player {} attempting summon for horse UUID={} storedPos={} dim={}",
                player.getGameProfile().getName(), horseUuid, storedPos, bound.dimension());

        ServerWorld storedWorld = requestor.getServer().getWorld(storedKey);
        if (storedWorld == null) {
            LOG.warn("[SUMMON] stored world {} is null", bound.dimension());
            player.sendMessage(Text.translatable("item.whistle.whistle.dimension_missing"), true);
            return ActionResult.SUCCESS;
        }

        // Quick direct check: maybe entity is loaded already
        Entity e = storedWorld.getEntity(horseUuid);
        if (e instanceof AbstractHorseEntity found) {
            LOG.info("[SUMMON] Found horse UUID={} already loaded in world {}", horseUuid, bound.dimension());
            handleFoundHorseOnRetry(found, requestor, player);
            return ActionResult.SUCCESS;
        }

        // Fallback: use persistent storage
        WhistleSummoningStorage storage = WhistleSummoningStorage.get(requestor);
        Optional<WhistleSummoningStorage.StoredHorse> storedOpt = storage.get(horseUuid);
        if (storedOpt.isEmpty()) {
            LOG.warn("[SUMMON] No stored entry for UUID={}", horseUuid);
            player.sendMessage(Text.translatable("item.whistle.whistle.not_found"), true);
            return ActionResult.SUCCESS;
        }
        WhistleSummoningStorage.StoredHorse stored = storedOpt.get();
        LOG.info("[SUMMON] Storage fallback: pos={} dim={}", stored.pos, stored.dimensionId);

        // add chunk ticket (use radius/level 31 so chunk is strongly loaded)
        ChunkPos cp = new ChunkPos(stored.pos);
        ServerChunkManager chunkManager = storedWorld.getChunkManager();

        try {
            // pass the player UUID as the ticket argument (fourth parameter) to match generic signature
            chunkManager.addTicket(WhistleMod.WHISTLE_TICKET, cp, 31, player.getUuid());
            // ensure chunk is requested at FULL so chunk data and entities are prepared
            storedWorld.getChunkManager().getChunk(cp.x, cp.z, ChunkStatus.FULL, true);
            LOG.info("[SUMMON] Added ticket for horse {} at chunk {} (dim {})", horseUuid, cp, stored.dimensionId);
        } catch (Throwable t) {
            LOG.warn("[SUMMON] Failed to add ticket with level; falling back to addTicket with arg 0: {}", t.getMessage());
            try {
                // fallback uses arg too (player UUID)
                chunkManager.addTicket(WhistleMod.WHISTLE_TICKET, cp, 0, player.getUuid());
            } catch (Throwable ignored) {}
        }

        // Schedule retry job (processed by WhistleMod on next ticks)
        WhistleMod.WhistleRetryJob job = new WhistleMod.WhistleRetryJob(
                horseUuid,
                player.getUuid(),
                RegistryKey.of(RegistryKeys.WORLD, stored.dimensionId),
                cp,
                MAX_RETRY_TICKS
        );
        WhistleMod.scheduleRetry(job);

        player.sendMessage(Text.translatable("item.whistle.whistle.delayed"), true);
        return ActionResult.SUCCESS;
    }

    public static void handleFoundHorseOnRetry(AbstractHorseEntity horse, ServerWorld requestorWorld, ServerPlayerEntity player) {
        BlockPos tp = player.getBlockPos();
        LOG.info("[SUMMON] Teleporting horse UUID={} to player {} at {}", horse.getUuid(), player.getGameProfile().getName(), tp);

        if (horse.getWorld() != requestorWorld) {
            horse.teleport(
                    requestorWorld,
                    tp.getX() + 0.5,
                    tp.getY(),
                    tp.getZ() + 0.5,
                    Set.of(net.minecraft.network.packet.s2c.play.PositionFlag.X,
                            net.minecraft.network.packet.s2c.play.PositionFlag.Y,
                            net.minecraft.network.packet.s2c.play.PositionFlag.Z),
                    player.getYaw(),
                    player.getPitch(),
                    true
            );
        } else {
            horse.requestTeleport(tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5);
        }

        ItemStack stack = player.getMainHandStack();
        if (stack.getItem() instanceof WhistleItem) {
            BoundHorseData newBound = new BoundHorseData(horse.getUuid(), requestorWorld.getRegistryKey().getValue(), player.getBlockPos());
            stack.set(ModDataComponents.BOUND_HORSE_DATA, newBound);
        }

        try {
            WhistleSummoningStorage storage = WhistleSummoningStorage.get(requestorWorld);
            NbtCompound horseTag = new NbtCompound();
            horse.saveNbt(horseTag);
            storage.addOrUpdateFromEntity(horse, requestorWorld.getRegistryKey().getValue(), player.getBlockPos(), horseTag, horse.isDead());
        } catch (Throwable t) {
            LOG.warn("[SUMMON] Failed to update storage snapshot after teleport: {}", t.getMessage());
        }

        requestorWorld.playSound(null, player.getBlockPos(), net.minecraft.sound.SoundEvents.ENTITY_HORSE_AMBIENT,
                net.minecraft.sound.SoundCategory.PLAYERS, 1.0f, 1.0f);
        player.getItemCooldownManager().set(player.getMainHandStack(), 200);
        player.sendMessage(Text.translatable("item.whistle.whistle.summoned"), true);
    }
}
