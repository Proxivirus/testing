package com.proxi.whistle.world.summoning;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.datafixer.DataFixTypes;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * World persistent storage for whistle/horse bindings.
 * Compatible with Fabric 1.21.4 (yarn-1.21.4+build.8).
 */
public class WhistleSummoningStorage extends PersistentState {
    public static final String SAVE_ID = "whistle_summoning";

    private final Map<UUID, StoredHorse> horses = new HashMap<>();

    // ----- Access -----

    /**
     * Obtain (or create) the storage for the given world.
     */
    public static WhistleSummoningStorage get(ServerWorld world) {
        PersistentStateManager ps = world.getPersistentStateManager();
        return ps.getOrCreate(TYPE, SAVE_ID);
    }

    /**
     * Required PersistentState.Type with non-null DataFixTypes.
     * We use a generic saved-data fixer type; if you later add DFU migrations,
     * switch to a dedicated type.
     */
    public static final PersistentState.Type<WhistleSummoningStorage> TYPE =
        new PersistentState.Type<>(
            WhistleSummoningStorage::new,
            (nbt, lookup) -> fromNbt(nbt),
            DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES // any non-null saved-data type works for custom state
        );

    // ----- Construction -----

    public WhistleSummoningStorage() {}

    // ----- Public API you were calling before -----

    public void addOrUpdateFromBinding(UUID uuid,
                                       Identifier dimensionId,
                                       BlockPos pos,
                                       @Nullable NbtCompound entityTag,
                                       boolean isDead) {
        StoredHorse sh = new StoredHorse(uuid, dimensionId, pos, isDead, entityTag);
        horses.put(uuid, sh);
        markDirty();
    }

    public void addOrUpdateFromEntity(Entity e,
                                      Identifier dimensionId,
                                      BlockPos pos,
                                      @Nullable NbtCompound entityTag,
                                      boolean isDead) {
        UUID uuid = e.getUuid();
        addOrUpdateFromBinding(uuid, dimensionId, pos, entityTag, isDead);
    }

    public Optional<StoredHorse> get(UUID uuid) {
        return Optional.ofNullable(horses.get(uuid));
    }

    public void remove(UUID uuid) {
        if (horses.remove(uuid) != null) {
            markDirty();
        }
    }

    public Map<UUID, StoredHorse> all() {
        return horses;
    }

    // ----- NBT (de)serialization -----

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        NbtList list = new NbtList();
        for (StoredHorse sh : horses.values()) {
            NbtCompound entry = new NbtCompound();
            sh.saveInto(entry);
            list.add(entry);
        }
        nbt.put("horses", list);
        return nbt;
    }

    private static WhistleSummoningStorage fromNbt(NbtCompound nbt) {
        WhistleSummoningStorage storage = new WhistleSummoningStorage();
        if (nbt.contains("horses", NbtElement.LIST_TYPE)) {
            NbtList list = nbt.getList("horses", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                NbtCompound entry = list.getCompound(i);
                StoredHorse sh = StoredHorse.load(entry);
                if (sh != null) {
                    storage.horses.put(sh.entityUuid, sh);
                }
            }
        }
        return storage;
    }

    // ----- Model -----

    public static class StoredHorse {
        public final UUID entityUuid;
        public final Identifier dimensionId; // store as id string; resolve to RegistryKey<World> at use-site
        public final BlockPos pos;
        public final boolean isDead;
        @Nullable public final NbtCompound entityTag; // optional cached entity NBT

        public StoredHorse(UUID entityUuid,
                           Identifier dimensionId,
                           BlockPos pos,
                           boolean isDead,
                           @Nullable NbtCompound entityTag) {
            this.entityUuid = entityUuid;
            this.dimensionId = dimensionId;
            this.pos = pos;
            this.isDead = isDead;
            this.entityTag = entityTag;
        }

        public void saveInto(NbtCompound tag) {
            tag.putUuid("uuid", entityUuid);
            tag.putString("dimension", dimensionId.toString());
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            tag.putBoolean("dead", isDead);
            if (entityTag != null) {
                tag.put("entity", entityTag.copy()); // defensive copy
            }
        }

        public static @Nullable StoredHorse load(NbtCompound tag) {
            if (!tag.containsUuid("uuid") || !tag.contains("dimension", NbtElement.STRING_TYPE)) {
                return null;
            }
            UUID uuid = tag.getUuid("uuid");
            Identifier dim = Identifier.of(tag.getString("dimension"));
            int x = tag.getInt("x");
            int y = tag.getInt("y");
            int z = tag.getInt("z");
            boolean dead = tag.getBoolean("dead");
            NbtCompound entity = tag.contains("entity", NbtElement.COMPOUND_TYPE)
                    ? tag.getCompound("entity").copy()
                    : null;
            return new StoredHorse(uuid, dim, new BlockPos(x, y, z), dead, entity);
        }
    }
}
