package net.whistlemod.item;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.passive.AbstractHorseEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.TypedActionResult;
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
    public TypedActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
        if (user.isSneaking() && entity instanceof AbstractHorseEntity horse) {
            if (horse.isTame() && horse.getOwnerUuid().equals(user.getUuid())) {
                NbtCompound tag = stack.getOrCreateNbt();
                tag.putUuid(BOUND_HORSE_KEY, horse.getUuid());
                
                WhistleSummoning.get(user.getServer()).bindHorse(horse);
                user.sendMessage(Text.translatable("whistle.horse_bound"), true);
                return TypedActionResult.success(stack, world.isClient());
            }
        }
        return TypedActionResult.pass(stack);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);
        NbtCompound tag = stack.getNbt();
        
        if (tag != null && tag.containsUuid(BOUND_HORSE_KEY)) {
            if (user instanceof ServerPlayerEntity serverPlayer) {
                UUID horseId = tag.getUuid(BOUND_HORSE_KEY);
                CallResult result = WhistleSummoning.get(serverPlayer.getServer())
                                    .summonHorse(serverPlayer, horseId);
                
                handleSummonResult(user, result);
            }
            return TypedActionResult.success(stack);
        }
        return TypedActionResult.pass(stack);
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