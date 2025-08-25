package com.proxi.whistle.item;

import com.proxi.whistle.component.BoundHorseData;
import com.proxi.whistle.component.ModDataComponents;
import com.proxi.whistle.util.ItemStackNbtUtil;
import com.proxi.whistle.world.BoundEntityStorage;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.item.Item.TooltipContext;
import net.minecraft.util.Formatting;
import net.minecraft.entity.LivingEntity;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class WhistleItem extends Item {

    private static final boolean ALLOW_CROSS_DIMENSION = true;
    private static final double MAX_SUMMON_DISTANCE = Double.POSITIVE_INFINITY;

    public WhistleItem(Settings settings) {
        super(settings.maxCount(1));
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        BoundHorseData bound = stack.get(ModDataComponents.BOUND_HORSE_DATA);

        if (bound == null) {
            if (!world.isClient) user.sendMessage(Text.translatable("item.whistle.whistle.unbound"), true);
            return ActionResult.FAIL;
        }

        UUID horseUuid = bound.uuid();
        Identifier horseDimId = bound.dimension();
        BlockPos storedPos = bound.pos();

        if (!world.isClient) {
            ServerWorld currentServerWorld = (ServerWorld) world;
            RegistryKey<World> horseWorldKey = RegistryKey.of(RegistryKeys.WORLD, horseDimId);
            ServerWorld horseWorld = currentServerWorld.getServer().getWorld(horseWorldKey);

            if (horseWorld == null) {
                user.sendMessage(Text.translatable("item.whistle.whistle.dimension_missing"), true);
                return ActionResult.FAIL;
            }

            if (!ALLOW_CROSS_DIMENSION && !horseWorld.getRegistryKey().equals(currentServerWorld.getRegistryKey())) {
                user.sendMessage(Text.translatable("item.whistle.crossdim_disabled"), true);
                return ActionResult.FAIL;
            }

            AbstractHorseEntity horse = null;
            Entity maybe = horseWorld.getEntity(horseUuid);
            if (maybe instanceof AbstractHorseEntity found) {
                horse = found;
            } else {
                ChunkPos chunkPos = new ChunkPos(storedPos);
                if (horseWorld.isChunkLoaded(chunkPos.x, chunkPos.z)) {
                    maybe = horseWorld.getEntity(horseUuid);
                    if (maybe instanceof AbstractHorseEntity found2) horse = found2;
                } else {
                    if (horseWorld == currentServerWorld) {
                        horseWorld.getChunkManager().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, true);
                        maybe = horseWorld.getEntity(horseUuid);
                        if (maybe instanceof AbstractHorseEntity found2) horse = found2;
                    }
                }
            }

            if (!horseWorld.getRegistryKey().equals(currentServerWorld.getRegistryKey())) {
                NbtCompound snapshot = BoundEntityStorage.getSnapshotNbt(horseUuid);
                if (snapshot == null || BoundEntityStorage.isDead(horseUuid)) {
                    user.sendMessage(Text.translatable("item.whistle.whistle.not_found"), true);
                    return ActionResult.FAIL;
                }

                UUID newUuid = BoundEntityStorage.recreateFromSnapshot(horseUuid, currentServerWorld, user.getX(), user.getY(), user.getZ(), user.getYaw(), user.getPitch());
                if (newUuid == null) {
                    user.sendMessage(Text.translatable("item.whistle.whistle.recreate_failed"), true);
                    return ActionResult.FAIL;
                }

                BoundHorseData newData = new BoundHorseData(newUuid, currentServerWorld.getRegistryKey().getValue(), user.getBlockPos());
                stack.set(ModDataComponents.BOUND_HORSE_DATA, newData);

                // Also update client-visible NBT so the tooltip updates immediately
                writeBindingNbt(stack, newUuid, currentServerWorld.getRegistryKey().getValue(), user.getBlockPos());

                currentServerWorld.playSound(null, user.getBlockPos(), SoundEvents.ENTITY_HORSE_AMBIENT, SoundCategory.PLAYERS, 1.0f, 1.0f);
                user.getItemCooldownManager().set(stack, 200);
                user.sendMessage(Text.translatable("item.whistle.whistle.summoned"), true);
                return ActionResult.SUCCESS;
            }

            if (horse != null) {
                if (horse.getWorld() != currentServerWorld) {
                    horse.teleport(currentServerWorld, user.getX(), user.getY(), user.getZ(),
                            Set.of(PositionFlag.X, PositionFlag.Y, PositionFlag.Z),
                            user.getYaw(), user.getPitch(), true);
                } else {
                    horse.requestTeleport(user.getX(), user.getY(), user.getZ());
                }

                BoundHorseData newData = new BoundHorseData(horseUuid, currentServerWorld.getRegistryKey().getValue(), user.getBlockPos());
                stack.set(ModDataComponents.BOUND_HORSE_DATA, newData);

                writeBindingNbt(stack, horseUuid, currentServerWorld.getRegistryKey().getValue(), user.getBlockPos());

                currentServerWorld.playSound(null, user.getBlockPos(), SoundEvents.ENTITY_HORSE_AMBIENT, SoundCategory.PLAYERS, 1.0f, 1.0f);
                user.getItemCooldownManager().set(stack, 200);
                user.sendMessage(Text.translatable("item.whistle.whistle.summoned"), true);
                return ActionResult.SUCCESS;
            }

            NbtCompound snapshot = BoundEntityStorage.getSnapshotNbt(horseUuid);
            if (snapshot == null || BoundEntityStorage.isDead(horseUuid)) {
                user.sendMessage(Text.translatable("item.whistle.whistle.not_found"), true);
                return ActionResult.FAIL;
            }

            UUID recreated = BoundEntityStorage.recreateFromSnapshot(horseUuid, currentServerWorld, user.getX(), user.getY(), user.getZ(), user.getYaw(), user.getPitch());
            if (recreated == null) {
                user.sendMessage(Text.translatable("item.whistle.whistle.recreate_failed"), true);
                return ActionResult.FAIL;
            }

            BoundHorseData newData = new BoundHorseData(recreated, currentServerWorld.getRegistryKey().getValue(), user.getBlockPos());
            stack.set(ModDataComponents.BOUND_HORSE_DATA, newData);

            writeBindingNbt(stack, recreated, currentServerWorld.getRegistryKey().getValue(), user.getBlockPos());

            currentServerWorld.playSound(null, user.getBlockPos(), SoundEvents.ENTITY_HORSE_AMBIENT, SoundCategory.PLAYERS, 1.0f, 1.0f);
            user.getItemCooldownManager().set(stack, 200);
            user.sendMessage(Text.translatable("item.whistle.whistle.summoned"), true);
            return ActionResult.SUCCESS;
        }

        return ActionResult.FAIL;
    }

    @Override
    public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
        BoundHorseData data = stack.get(ModDataComponents.BOUND_HORSE_DATA);
        if (data != null) {
            UUID uuid = data.uuid();

            BoundHorseData latest = BoundEntityStorage.getLatestData(uuid);
            if (latest != null) {
                tooltip.add(Text.literal("Dimension: " + latest.dimension()).formatted(Formatting.GRAY));
                tooltip.add(Text.literal("Position: " + latest.pos().toShortString()).formatted(Formatting.GRAY));
            } else {
                NbtCompound root = ItemStackNbtUtil.getNbt(stack);
                if (root != null && root.contains("WhistleBoundHorse")) {
                    NbtCompound bh = root.getCompound("WhistleBoundHorse");
                    try {
                        String dim = bh.getString("dimension");
                        int x = bh.getInt("x");
                        int y = bh.getInt("y");
                        int z = bh.getInt("z");
                        tooltip.add(Text.literal("Dimension: " + dim).formatted(Formatting.GRAY));
                        tooltip.add(Text.literal("Position: " + new BlockPos(x, y, z).toShortString()).formatted(Formatting.GRAY));
                    } catch (Exception ignored) {
                        tooltip.add(Text.literal("Dimension: " + data.dimension()).formatted(Formatting.GRAY));
                        tooltip.add(Text.literal("Position: " + data.pos().toShortString()).formatted(Formatting.GRAY));
                    }
                } else {
                    tooltip.add(Text.literal("Dimension: " + data.dimension()).formatted(Formatting.GRAY));
                    tooltip.add(Text.literal("Position: " + data.pos().toShortString()).formatted(Formatting.GRAY));
                }
            }

            if (BoundEntityStorage.isDead(uuid)) {
                tooltip.add(Text.translatable("item.whistle.whistle.dead").formatted(Formatting.RED));
            }

            String offline = BoundEntityStorage.getOfflinePlayerName(uuid);
            if (offline != null) {
                tooltip.add(Text.literal("Ridden by (offline): " + offline).formatted(Formatting.YELLOW));
            }
        } else {
            tooltip.add(Text.translatable("item.whistle.whistle.not_bound").formatted(Formatting.RED));
        }
    }

    private static void writeBindingNbt(ItemStack stack, UUID uuid, Identifier dim, BlockPos pos) {
        NbtCompound root = ItemStackNbtUtil.getOrCreateNbt(stack);
        NbtCompound bh = new NbtCompound();
        bh.putUuid("uuid", uuid);
        bh.putString("dimension", dim.toString());
        bh.putInt("x", pos.getX());
        bh.putInt("y", pos.getY());
        bh.putInt("z", pos.getZ());
        root.put("WhistleBoundHorse", bh);
        ItemStackNbtUtil.setNbt(stack, root);
    }
	
	public static UUID readBoundUuid(NbtCompound bound) {
		if (bound == null) return null;

		// Prefer lowercase 'uuid' (what writeBindingNbt uses), but support legacy uppercase.
		try {
			if (bound.containsUuid("uuid")) {
				return bound.getUuid("uuid");
			}
			if (bound.containsUuid("UUID")) {
				return bound.getUuid("UUID");
			}
		} catch (Throwable ignored) {}

		// Legacy support if split into most/least
		if (bound.contains("UUIDMost") && bound.contains("UUIDLeast")) {
			try {
				return new UUID(bound.getLong("UUIDMost"), bound.getLong("UUIDLeast"));
			} catch (Throwable ignored) {}
		}
		return null;
	}



	public static void tryRelink(ItemStack stack, World world) {
		if (!(world instanceof ServerWorld serverWorld)) return;

		// Use ItemStackNbtUtil so we don't depend on mapping-specific method names
		NbtCompound nbt = ItemStackNbtUtil.getNbt(stack);
		if (nbt == null) return;

		// Support both current key and legacy key
		NbtCompound bound = null;
		if (nbt.contains("WhistleBoundHorse") && nbt.get("WhistleBoundHorse") instanceof NbtCompound) {
			bound = nbt.getCompound("WhistleBoundHorse");
		} else if (nbt.contains("BoundEntity") && nbt.get("BoundEntity") instanceof NbtCompound) {
			bound = nbt.getCompound("BoundEntity");
		} else {
			return;
		}

		UUID uuid = readBoundUuid(bound);
		if (uuid == null) return;

		Entity e = serverWorld.getEntity(uuid);
		if (e instanceof LivingEntity living && !living.isDead()) {
			// Refresh the snapshot now — relink to live entity and update the item NBT
			writeBindingNbt(stack, living.getUuid(), serverWorld.getRegistryKey().getValue(), living.getBlockPos());
		}
	}


}
