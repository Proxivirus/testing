package net.whistlemod;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.JanksonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.whistlemod.event.ModEvents;
import net.whistlemod.item.HorseWhistleItem;

public class WhistleMod implements ModInitializer {
    public static final String MOD_ID = "whistlemod";
    public static final Item HORSE_WHISTLE = new HorseWhistleItem(new Item.Settings());
    public static ModConfig CONFIG;

    @Override
    public void onInitialize() {
        Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "horse_whistle"), HORSE_WHISTLE);
        AutoConfig.register(ModConfig.class, JanksonConfigSerializer::new);
        CONFIG = AutoConfig.getConfigHolder(ModConfig.class).getConfig();
        ModEvents.register();
    }
}