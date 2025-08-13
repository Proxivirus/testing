package net.whistlemod.event;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.HorseBaseEntity;
import net.minecraft.server.world.ServerWorld;
import net.whistlemod.world.summoning.WhistleSummoning;

public class ModEvents {
    public static void register() {
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            // Handle player dimension changes if needed
        });
        
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof HorseBaseEntity horse) {
                WhistleSummoning summoning = WhistleSummoning.get(entity.getServer());
                if (summoning.isHorseBound(horse.getUuid())) {
                    summoning.markHorseDead(horse.getUuid());
                }
            }
        });
    }
}