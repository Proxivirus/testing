package net.whistlemod.world.summoning;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.AbstractHorseEntity;
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
        ServerWorld world = server.getOverworld();
        return world.getPersistentStateManager().getOrCreate(
            type -> new WhistleSummoning(),
            () -> new WhistleSummoning(),
            WhistleMod.MOD_ID
        );
    }

    public void bindHorse(AbstractHorseEntity horse) {
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
        AbstractHorseEntity existingHorse = findLoadedHorse(player.getServer(), horseId);
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
        BlockPos horseBlockPos = BlockPos.ofFloored(horsePos);
        
        return playerPos.getSquaredDistance(horseBlockPos) > maxDistance * maxDistance;
    }

	private CallResult teleportHorse(ServerPlayerEntity player, AbstractHorseEntity horse) {
		if (!player.getWorld().getRegistryKey().equals(horse.getWorld().getRegistryKey())) {
			ServerWorld targetLevel = player.getWorld();
			horse.moveToWorld(targetLevel);
		}
		
		horse.teleport(player.getX(), player.getY(), player.getZ());
		horse.getNavigation().stop();
		return CallResult.SUCCESS;
	}

	private CallResult summonFromStorage(ServerPlayerEntity player, StoredBoundHorse boundHorse) {
		ServerWorld targetLevel = (ServerWorld) player.getWorld();
		RegistryKey<World> horseDimension = boundHorse.getDimension();
		
		if (!player.getWorld().getRegistryKey().equals(horseDimension)) {
			ServerWorld horseLevel = player.getServer().getWorld(horseDimension);
			if (horseLevel == null) return CallResult.INVALID_DIMENSION;
			
			AbstractHorseEntity horse = createHorseFromStorage(boundHorse, horseLevel);
			if (horse == null) return CallResult.ERROR_ENTITY_NOT_CREATED;
			
			horse.moveToWorld(targetLevel);
			horse.teleport(player.getX(), player.getY(), player.getZ());
		} else {
			AbstractHorseEntity horse = createHorseFromStorage(boundHorse, targetLevel);
			if (horse == null) return CallResult.ERROR_ENTITY_NOT_CREATED;
			horse.teleport(player.getX(), player.getY(), player.getZ());
		}
		
		return CallResult.SUCCESS;
	}
    
	@Nullable
	private AbstractHorseEntity createHorseFromStorage(StoredBoundHorse stored, ServerWorld level) {
		NbtCompound tag = stored.getHorseData();
		Entity entity = EntityType.loadEntityWithPassengers(tag, level, (e) -> e);
		
		if (!(entity instanceof AbstractHorseEntity horse)) return null;
		
		level.spawnEntity(entity);
		return horse;
	}
    
	@Nullable
	private AbstractHorseEntity findLoadedHorse(MinecraftServer server, UUID horseId) {
		for (ServerWorld level : server.getWorlds()) {
			Entity entity = level.getEntity(horseId);
			if (entity instanceof AbstractHorseEntity horse) {
				return horse;
			}
		}
		return null;
	}
    
    @Override
    public NbtCompound writeNbt(NbtCompound tag) {
        // Save data to NBT
        return tag;
    }
	
	public boolean isHorseBound(UUID horseId) {
		return boundHorses.containsKey(horseId);
	}

	public void markHorseDead(UUID horseId) {
		StoredBoundHorse horse = boundHorses.get(horseId);
		if (horse != null) {
			horse.setDead(true);
			markDirty();
		}
	}
	    public static final PersistentState.Type<WhistleSummoning> TYPE = new PersistentState.Type<>(
        WhistleSummoning::new,
        WhistleSummoning::fromNbt,
        null
    );

    public static WhistleSummoning fromNbt(NbtCompound tag) {
        WhistleSummoning data = new WhistleSummoning();
        // Load data from NBT
        return data;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound tag) {
        // Save data to NBT
        return tag;
    }

    public static WhistleSummoning get(MinecraftServer server) {
        ServerWorld world = server.getOverworld();
        return world.getPersistentStateManager().getOrCreate(TYPE, WhistleMod.MOD_ID);
    }
}