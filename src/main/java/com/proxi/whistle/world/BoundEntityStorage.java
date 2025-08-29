package com.proxi.whistle.world;

import com.proxi.whistle.component.BoundHorseData;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * BoundEntityStorage
 *
 * Keeps in-memory snapshots of bound entities and persists them into the world save
 * as a single compressed NBT file (data/whistle_bound_entities.nbt).
 *
 * This version avoids SavedData / PersistentState API mapping differences by writing
 * a file directly in the world save. It's simple, robust and visible to server admins.
 */
public final class BoundEntityStorage {
    private BoundEntityStorage() {}

    // ---------- in-memory state ----------
    private static final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> originalToRecreated = new ConcurrentHashMap<>();
    private static final Set<UUID> originalsToDelete = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, String> withOfflinePlayer = new ConcurrentHashMap<>();

    // persistence
    private static volatile Path persistenceFile = null;
    private static volatile boolean persistenceEnabled = false;

    private static final class Snapshot {
        volatile NbtCompound nbt;
        volatile Identifier dimension;
        volatile BlockPos pos;
        volatile boolean loaded = false;
        volatile boolean dead = false;
        volatile long lastUpdatedTick = 0;

        Snapshot(NbtCompound nbt, Identifier dimension, BlockPos pos) {
            this.nbt = nbt;
            this.dimension = dimension;
            this.pos = pos;
        }
    }

    public static void init() {
        // kept for compatibility
    }

    // ----------------- Persistence API (file-based) -----------------

    /**
     * Initialize file-based persistence. Call on server started.
     * This will set the persistence file (world/data/whistle_bound_entities.nbt),
     * load it if present, and enable markDirty() -> save behavior.
     */
    public static void initPersistence(MinecraftServer server) {
        try {
            if (server == null) return;
            Path root = server.getSavePath(WorldSavePath.ROOT);
            if (root == null) return;
            Path dataDir = root.resolve("data");
            Files.createDirectories(dataDir);
            persistenceFile = dataDir.resolve("whistle_bound_entities.nbt");
            persistenceEnabled = true;

            // load if present
            File f = persistenceFile.toFile();
            if (f.exists() && f.isFile()) {
                try {
                    NbtCompound rootNbt = NbtIo.readCompressed(persistenceFile, NbtSizeTracker.ofUnlimitedBytes());
                    if (rootNbt != null) importFromNbt(rootNbt);
                } catch (Throwable t) {
                    // if the file is corrupted, back it up and continue
                    try {
                        Path bad = dataDir.resolve("whistle_bound_entities.nbt.corrupt");
                        Files.move(persistenceFile, bad);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable t) {
            persistenceEnabled = false;
            persistenceFile = null;
        }
    }

    /**
     * Force immediate flush to disk (blocking). Safe to call on server thread at shutdown.
     */
    public static void flushToDisk() {
        if (!persistenceEnabled || persistenceFile == null) return;
        try {
            NbtCompound out = exportToNbt();
            // write to temp + atomic move
            Path tmp = persistenceFile.resolveSibling(persistenceFile.getFileName() + ".tmp");
            File tmpf = tmp.toFile();
            NbtIo.writeCompressed(out, tmp);
            // atomic move (replace existing)
            Files.move(tmp, persistenceFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException io) {
            try {
                // fallback: try writing directly (non-atomic)
                NbtCompound out = exportToNbt();
                NbtIo.writeCompressed(out, persistenceFile);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static void markDirty() {
        // simple behavior: write on each change (safe but may be frequent).
        // If you want to throttle writes, replace this with a scheduled flush.
        if (!persistenceEnabled) return;
        try {
            flushToDisk();
        } catch (Throwable ignored) {}
    }

    // ----------------- Export / Import (persistence format) -----------------

    /**
     * Build NBT root that contains our serialized state.
     */
    public static NbtCompound exportToNbt() {
        NbtCompound root = new NbtCompound();
        root.putInt("version", 1);

        NbtList entries = new NbtList();
        for (Map.Entry<UUID, Snapshot> e : snapshots.entrySet()) {
            UUID id = e.getKey();
            Snapshot s = e.getValue();
            try {
                NbtCompound ent = new NbtCompound();
                ent.putString("uuid", id.toString());
                ent.put("nbt", s.nbt != null ? s.nbt.copy() : new NbtCompound());
                ent.putString("dimension", s.dimension != null ? s.dimension.toString() : "minecraft:overworld");
                ent.putInt("x", s.pos != null ? s.pos.getX() : 0);
                ent.putInt("y", s.pos != null ? s.pos.getY() : 0);
                ent.putInt("z", s.pos != null ? s.pos.getZ() : 0);
                ent.putBoolean("loaded", s.loaded);
                ent.putBoolean("dead", s.dead);
                String offline = withOfflinePlayer.get(id);
                if (offline != null) ent.putString("offlinePlayer", offline);
                entries.add(ent);
            } catch (Throwable ignored) {}
        }
        root.put("entries", entries);

        NbtList mappings = new NbtList();
        for (Map.Entry<UUID, UUID> map : originalToRecreated.entrySet()) {
            NbtCompound m = new NbtCompound();
            m.putString("original", map.getKey().toString());
            m.putString("recreated", map.getValue().toString());
            mappings.add(m);
        }
        root.put("originalMappings", mappings);

        NbtList strings = new NbtList();
        for (UUID u : originalsToDelete) strings.add(NbtString.of(u.toString()));
        root.put("originalsToDelete", strings);

        return root;
    }

    /**
     * Load NBT from file and populate in-memory structures.
     */
    public static void importFromNbt(NbtCompound root) {
        try {
            if (root == null) return;
            int version = root.contains("version") ? root.getInt("version") : 1;

            snapshots.clear();
            originalToRecreated.clear();
            originalsToDelete.clear();
            withOfflinePlayer.clear();

            if (root.contains("entries")) {
                NbtList entries = root.getList("entries", NbtElement.COMPOUND_TYPE);
                for (int i = 0; i < entries.size(); i++) {
                    try {
                        NbtCompound ent = entries.getCompound(i);
                        String uuidStr = ent.getString("uuid");
                        if (uuidStr == null || uuidStr.isEmpty()) continue;
                        UUID id = UUID.fromString(uuidStr);
                        NbtCompound nbt = ent.contains("nbt") ? ent.getCompound("nbt") : new NbtCompound();
                        Identifier dim = Identifier.tryParse(ent.getString("dimension"));
                        int x = ent.getInt("x");
                        int y = ent.getInt("y");
                        int z = ent.getInt("z");
                        Snapshot s = new Snapshot(nbt, dim, new BlockPos(x, y, z));
                        s.loaded = ent.getBoolean("loaded");
                        s.dead = ent.getBoolean("dead");
						// NEW: do not carry process tick counters across saves
						s.lastUpdatedTick = 0L;
                        snapshots.put(id, s);
                        if (ent.contains("offlinePlayer")) {
                            withOfflinePlayer.put(id, ent.getString("offlinePlayer"));
                        }
                    } catch (Throwable ignored) {}
                }
            }

            if (root.contains("originalMappings")) {
                NbtList mappings = root.getList("originalMappings", NbtElement.COMPOUND_TYPE);
                for (int i = 0; i < mappings.size(); i++) {
                    try {
                        NbtCompound m = mappings.getCompound(i);
                        UUID orig = UUID.fromString(m.getString("original"));
                        UUID rec = UUID.fromString(m.getString("recreated"));
                        originalToRecreated.put(orig, rec);
                    } catch (Throwable ignored) {}
                }
            }

            if (root.contains("originalsToDelete")) {
                NbtList deletes = root.getList("originalsToDelete", NbtElement.STRING_TYPE);
                for (int i = 0; i < deletes.size(); i++) {
                    try {
                        String u = deletes.getString(i);
                        originalsToDelete.add(UUID.fromString(u));
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    // ----------------- Runtime API (mutations) -----------------

    public static void storeSnapshot(UUID uuid, NbtCompound nbt, Identifier dimension, BlockPos pos) {
        NbtCompound copy = nbt != null ? nbt.copy() : new NbtCompound();
        snapshots.put(uuid, new Snapshot(copy, dimension, pos));
        withOfflinePlayer.remove(uuid);
        markDirty();
    }

    public static void updateSnapshotFromEntity(Entity entity) {
        if (entity == null) return;
        UUID id = entity.getUuid();
        Snapshot s = snapshots.get(id);
        NbtCompound nbt = new NbtCompound();
        try {
            entity.saveNbt(nbt);
        } catch (Throwable ignored) {}
        Identifier dim = entity.getWorld().getRegistryKey().getValue();
        BlockPos pos = entity.getBlockPos();

        if (s == null) {
            snapshots.put(id, new Snapshot(nbt, dim, pos));
            markDirty();
            return;
        }
        s.nbt = nbt;
        s.pos = pos;
        s.dimension = dim;
        s.loaded = true;
        markDirty();
    }

    public static void onEntityUnload(Entity entity, ServerWorld world) {
        if (!(entity instanceof LivingEntity)) return;
        UUID id = entity.getUuid();
        NbtCompound nbt = new NbtCompound();
        try {
            entity.saveNbt(nbt);
        } catch (Throwable ignored) {}
        Identifier dim = world.getRegistryKey().getValue();
        BlockPos pos = entity.getBlockPos();
        Snapshot s = snapshots.computeIfAbsent(id, k -> new Snapshot(nbt, dim, pos));
        s.nbt = nbt;
        s.dimension = dim;
        s.pos = pos;
        s.loaded = false;
        markDirty();
    }

    public static void onEntityLoad(Entity entity, ServerWorld world) {
        UUID id = entity.getUuid();
        Snapshot s = snapshots.get(id);
        if (s != null) {
            s.loaded = true;
            s.dimension = world.getRegistryKey().getValue();
            s.pos = entity.getBlockPos();
            NbtCompound nbt = new NbtCompound();
            try {
                entity.saveNbt(nbt);
            } catch (Throwable ignored) {}
            s.nbt = nbt;
            s.dead = false;
            markDirty();
        }

        if (originalsToDelete.contains(id)) {
            try {
                entity.discard();
            } catch (Throwable t) {
                try {
                    entity.remove(Entity.RemovalReason.DISCARDED);
                } catch (Throwable ignored) {}
            }
            originalsToDelete.remove(id);
            snapshots.remove(id);
            originalToRecreated.remove(id);
            withOfflinePlayer.remove(id);
            markDirty();
        }
    }

    public static void markDead(UUID uuid) {
        Snapshot s = snapshots.get(uuid);
        if (s != null) s.dead = true;
        else {
            Snapshot ns = new Snapshot(new NbtCompound(), Identifier.tryParse("minecraft:overworld"), BlockPos.ORIGIN);
            ns.dead = true;
            snapshots.put(uuid, ns);
        }
        markDirty();
    }

    public static boolean isDead(UUID uuid) {
        Snapshot s = snapshots.get(uuid);
        return s != null && s.dead;
    }

    public static boolean isBound(UUID uuid) {
        return snapshots.containsKey(uuid);
    }

    public static void markWithOfflinePlayer(UUID uuid, String playerName) {
        withOfflinePlayer.put(uuid, playerName);
        markDirty();
    }

    public static String getOfflinePlayerName(UUID uuid) {
        return withOfflinePlayer.get(uuid);
    }

    /**
     * Recreate entity from the stored snapshot into the target world at x,y,z,yaw,pitch.
     * Returns the new entity UUID or null on failure.
     */
    public static UUID recreateFromSnapshot(UUID originalUuid, ServerWorld targetWorld, double x, double y, double z, float yaw, float pitch) {
        Snapshot s = snapshots.get(originalUuid);
        if (s == null || s.nbt == null) return null;
        try {
            NbtCompound nbtCopy = s.nbt.copy();

            // Remove UUID keys so the loader will give a fresh UUID
            nbtCopy.remove("UUID");
            nbtCopy.remove("UUIDMost");
            nbtCopy.remove("UUIDLeast");

            // Ensure Pos fields reflect target coords
            nbtCopy.putDouble("PosX", x);
            nbtCopy.putDouble("PosY", y);
            nbtCopy.putDouble("PosZ", z);

            SpawnReason reason = SpawnReason.TRIGGERED;

            Entity recreated = EntityType.loadEntityWithPassengers(nbtCopy, targetWorld, reason, (Function<Entity, Entity>) entity -> {
                entity.refreshPositionAndAngles(x, y, z, yaw, pitch);
                return entity;
            });

            if (recreated == null) return null;

            targetWorld.spawnEntity(recreated);

            UUID newUuid = recreated.getUuid();

            NbtCompound newNbt = new NbtCompound();
            try {
                recreated.saveNbt(newNbt);
            } catch (Throwable ignored) {}
            Identifier newDim = targetWorld.getRegistryKey().getValue();
            BlockPos newPos = recreated.getBlockPos();
            snapshots.put(newUuid, new Snapshot(newNbt, newDim, newPos));

            // record mapping so that if the original loads later, we can quietly discard it
            originalToRecreated.put(originalUuid, newUuid);

            // --- Attempt immediate removal of the original entity if it's currently loaded ---
            try {
                MinecraftServer server = targetWorld.getServer();
                if (s.dimension != null && server != null) {
                    RegistryKey<World> originalWorldKey = RegistryKey.of(RegistryKeys.WORLD, s.dimension);
                    ServerWorld originalWorld = server.getWorld(originalWorldKey);
                    if (originalWorld != null) {
                        Entity originalEntity = originalWorld.getEntity(originalUuid);
                        if (originalEntity != null && originalEntity.isAlive()) {
                            try {
                                originalEntity.discard();
                            } catch (Throwable t) {
                                try {
                                    originalEntity.remove(Entity.RemovalReason.DISCARDED);
                                } catch (Throwable ignored) {}
                            }
                            snapshots.remove(originalUuid);
                        } else {
                            originalsToDelete.add(originalUuid);
                        }
                    } else {
                        originalsToDelete.add(originalUuid);
                    }
                } else {
                    originalsToDelete.add(originalUuid);
                }
            } catch (Throwable t) {
                originalsToDelete.add(originalUuid);
            }

            markDirty();
            return newUuid;
        } catch (Throwable t) {
            return null;
        }
    }

    public static void tick(MinecraftServer server) {
        if (server == null) return;
        long tick = server.getTicks();

        for (Map.Entry<UUID, Snapshot> e : snapshots.entrySet()) {
            UUID uuid = e.getKey();
            Snapshot s = e.getValue();

			// NEW: handle server restarts (tick counter resets)
			long diff = tick - s.lastUpdatedTick;
			// Update if it's been >= 20 ticks OR if diff went negative (server restarted)
			if (diff < 0 || diff < 20) continue;
			s.lastUpdatedTick = tick;

            if (s.dimension == null) continue;
            RegistryKey<World> worldKey = RegistryKey.of(RegistryKeys.WORLD, s.dimension);
            ServerWorld w = server.getWorld(worldKey);
            if (w == null) continue;

            Entity ent = w.getEntity(uuid);
            if (ent instanceof AbstractHorseEntity || ent instanceof LivingEntity) {
                try {
                    NbtCompound nbt = new NbtCompound();
                    ent.saveNbt(nbt);
                    s.nbt = nbt;
                    s.pos = ent.getBlockPos();
                    s.loaded = true;
                    s.dead = false;
                    markDirty();
                } catch (Throwable ignored) {}
            } else {
                s.loaded = false;
            }
        }
    }

    public static BoundHorseData getLatestData(UUID uuid) {
        Snapshot s = snapshots.get(uuid);
        if (s == null) return null;
        return new BoundHorseData(uuid, s.dimension, s.pos);
    }

    public static NbtCompound getSnapshotNbt(UUID uuid) {
        Snapshot s = snapshots.get(uuid);
        if (s == null) return null;
        return s.nbt != null ? s.nbt.copy() : null;
    }

    public static UUID getRecreatedForOriginal(UUID original) {
        return originalToRecreated.get(original);
    }

    public static boolean isOriginalToDelete(UUID uuid) {
        return originalsToDelete.contains(uuid);
    }

    public static void remove(UUID uuid) {
        snapshots.remove(uuid);
        withOfflinePlayer.remove(uuid);
        originalsToDelete.remove(uuid);
        originalToRecreated.remove(uuid);
        markDirty();
    }
}
