package net.whistlemod.world.summoning;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.HorseBaseEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.PersistentState;
import net.whistlemod.WhistleMod;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class WhistleSummoning extends PersistentState {
    private final Map<UUID, StoredBoundHorse> boundHorses = new HashMap<>();
    private final Set<UUID> horsesToRemove = new HashSet<>();
    private final Set<UUID> unboundHorses = new HashSet<>();

    private static WhistleSummoning instance;

    public static WhistleSummoning get(MinecraftServer server) {
        ServerWorld world = server.getWorld(World.OVERWORLD);
        return world.getPersistentStateManager().getOrCreate(WhistleSummoning::fromNbt, WhistleSummoning::new, WhistleMod.MOD_ID);
    }

    public void bindHorse(HorseBaseEntity horse) {
        boundHorses.put(horse.getUuid(), new StoredBoundHorse(horse));
        markDirty();
    }

    public CallResult summonHorse(ServerPlayerEntity player, UUID horseId) {
        StoredBoundHorse boundHorse = boundHorses.get(horseId);
        if (boundHorse == null) return CallResult.NO_BOUND_HORSE;
        if (boundHorse.isDead()) return CallResult.HORSE_IS_DEAD;
        
        if (!dimensionsAreValid(player, boundHorse)) {
            return CallResult.INVALID_DIMENSION;
        }
        
        if (isTooFar(player, boundHorse)) {
            return CallResult.TOO_FAR;
        }

        // Try to find loaded horse first
        HorseBaseEntity existingHorse = findLoadedHorse(player.getServer(), horseId);
        if (existingHorse != null) {
            return teleportHorse(player, existingHorse);
        } else {
            return summonFromStorage(player, boundHorse);
        }
    }

    private boolean dimensionsAreValid(ServerPlayerEntity player, StoredBoundHorse boundHorse) {
        RegistryKey<World> playerDimension = player.getWorld().getRegistryKey();
        RegistryKey<World> horseDimension = boundHorse.getDimension();
        
        return switch (WhistleMod.CONFIG.whistleDimensionHandling) {
            case ANY -> true;
            case SAME -> playerDimension.equals(horseDimension);
            case WHITELIST -> Arrays.asList(WhistleMod.CONFIG.whistleDimensions).contains(
                playerDimension.getValue().toString());
            case BLACKLIST -> !Arrays.asList(WhistleMod.CONFIG.whistleDimensions).contains(
                playerDimension.getValue().toString());
        };
    }

    private boolean isTooFar(ServerPlayerEntity player, StoredBoundHorse boundHorse) {
        int maxDistance = WhistleMod.CONFIG.whistleMaxDistance;
        if (maxDistance < 0) return false;
        
        if (!player.getWorld().getRegistryKey().equals(boundHorse.getDimension())) {
            return true;
        }
        
        BlockPos playerPos = player.getBlockPos();
        Vec3d horsePos = boundHorse.getPosition();
        BlockPos horseBlockPos = new BlockPos((int) horsePos.x, (int) horsePos.y, (int) horsePos.z);
        
        return playerPos.getSquaredDistance(horseBlockPos) > maxDistance * maxDistance;
    }


    private CallResult teleportHorse(ServerPlayer player, AbstractHorse horse) {
        if (!player.level().dimension().equals(horse.level().dimension())) {
            ServerLevel targetLevel = player.serverLevel();
            horse.changeDimension(targetLevel);
        }
        
        horse.teleportTo(player.getX(), player.getY(), player.getZ());
        horse.getNavigation().stop();
        return CallResult.SUCCESS;
    }

    private CallResult summonFromStorage(ServerPlayer player, StoredBoundHorse boundHorse) {
        ServerLevel targetLevel = player.serverLevel();
        ResourceKey<Level> horseDimension = boundHorse.getDimension();
        
        if (!player.level().dimension().equals(horseDimension)) {
            ServerLevel horseLevel = player.getServer().getLevel(horseDimension);
            if (horseLevel == null) return CallResult.INVALID_DIMENSION;
            
            AbstractHorse horse = createHorseFromStorage(boundHorse, horseLevel);
            if (horse == null) return CallResult.ERROR_ENTITY_NOT_CREATED;
            
            horse.changeDimension(targetLevel);
            horse.teleportTo(player.getX(), player.getY(), player.getZ());
        } else {
            AbstractHorse horse = createHorseFromStorage(boundHorse, targetLevel);
            if (horse == null) return CallResult.ERROR_ENTITY_NOT_CREATED;
            horse.teleportTo(player.getX(), player.getY(), player.getZ());
        }
        
        return CallResult.SUCCESS;
    }
    
    @Nullable
    private AbstractHorse createHorseFromStorage(StoredBoundHorse stored, ServerLevel level) {
        CompoundTag tag = stored.getHorseData();
        Optional<EntityType<?>> type = EntityType.by(tag);
        if (type.isEmpty()) return null;
        
        Entity entity = type.get().create(level);
        if (!(entity instanceof AbstractHorse horse)) return null;
        
        horse.load(tag);
        horse.setUUID(UUID.randomUUID());
        level.addFreshEntity(horse);
        return horse;
    }
    
    @Nullable
    private AbstractHorse findLoadedHorse(MinecraftServer server, UUID horseId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(horseId) instanceof AbstractHorse horse) {
                return horse;
            }
        }
        return null;
    }
    
    // SavedData methods
    public static WhistleSummoning load(CompoundTag tag) {
        WhistleSummoning data = new WhistleSummoning();
        // Load data from NBT
        return data;
    }
    
    @Override
    public CompoundTag save(CompoundTag tag) {
        // Save data to NBT
        return tag;
    }
}