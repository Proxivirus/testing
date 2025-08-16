package net.whistlemod.world.summoning;

import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import java.util.UUID;

public class StoredBoundHorse {
    private final UUID horseId;
    private final NbtCompound horseData;
    private final Vec3d position;
    private final RegistryKey<World> dimension;
    private final boolean isDead;

	// Add setter
	public void setDead(boolean dead) {
		isDead = dead;
	}

    public StoredBoundHorse(AbstractHorseEntity horse) {
        this.horseId = horse.getUuid();
        this.horseData = saveHorseData(horse);
        this.position = horse.getPos();
        this.dimension = horse.getWorld().getRegistryKey();
        this.isDead = horse.isDead();
    }

    private NbtCompound saveHorseData(AbstractHorseEntity horse) {
        NbtCompound tag = new NbtCompound();
        horse.writeNbt(tag);
        return tag;
    }

    // Getters
    public UUID getHorseId() { return horseId; }
    public NbtCompound getHorseData() { return horseData; }
    public Vec3d getPosition() { return position; }
    public RegistryKey<World> getDimension() { return dimension; }
    public boolean isDead() { return isDead; }
}