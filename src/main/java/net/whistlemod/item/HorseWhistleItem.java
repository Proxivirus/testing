package net.whistlemod.item;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.whistlemod.world.summoning.CallResult;
import net.whistlemod.world.summoning.WhistleSummoning;

import java.util.UUID;

public class HorseWhistleItem extends Item {
    private static final String BOUND_HORSE_KEY = "BoundHorse";

    public HorseWhistleItem(Settings settings) {
        super(settings);
    }

    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
        if (user.isSneaking() && entity instanceof AbstractHorseEntity horse) {
            if (horse.isTame() && horse.getOwnerUuid().equals(user.getUuid())) {
                // Create NBT component to store horse UUID
                NbtCompound tag = new NbtCompound();
                tag.putUuid(BOUND_HORSE_KEY, horse.getUuid());
                stack.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(tag));
                
                WhistleSummoning.get(user.getServer()).bindHorse(horse);
                user.sendMessage(Text.translatable("whistle.horse_bound"), true);
                return ActionResult.SUCCESS;
            }
        }
        return ActionResult.PASS;
    }

    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        NbtComponent component = stack.get(DataComponentTypes.CUSTOM_DATA);
        
        if (component != null) {
            NbtCompound tag = component.copyNbt();
            if (tag.containsUuid(BOUND_HORSE_KEY)) {
                if (user instanceof ServerPlayerEntity serverPlayer) {
                    UUID horseId = tag.getUuid(BOUND_HORSE_KEY);
                    CallResult result = WhistleSummoning.get(serverPlayer.getServer())
                                        .summonHorse(serverPlayer, horseId);
                    
                    handleSummonResult(user, result);
                }
                return ActionResult.SUCCESS;
            }
        }
        return ActionResult.PASS;
    }

    private void handleSummonResult(PlayerEntity player, CallResult result) {
        switch (result) {
            case SUCCESS -> player.sendMessage(Text.translatable("whistle.summon_success"), true);
            case INVALID_DIMENSION -> player.sendMessage(Text.translatable("whistle.invalid_dimension"), true);
            case TOO_FAR -> player.sendMessage(Text.translatable("whistle.too_far"), true);
            case HORSE_IS_DEAD -> player.sendMessage(Text.translatable("whistle.horse_dead"), true);
            case NO_BOUND_HORSE -> player.sendMessage(Text.translatable("whistle.no_horse_bound"), true);
            default -> player.sendMessage(Text.translatable("whistle.summon_failed"), true);
        }
    }
}