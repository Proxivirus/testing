package net.whistlemod;

import net.fabricmc.api.ModInitializer;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.whistlemod.event.ModEvents;
import net.whistlemod.item.HorseWhistleItem;

public class WhistleMod implements ModInitializer {
    public static final String MOD_ID = "whistlemod";
    public static Item HORSE_WHISTLE;
    
    // Create registry key before item construction
    public static final RegistryKey<Item> HORSE_WHISTLE_KEY = 
        RegistryKey.of(RegistryKeys.ITEM, Identifier.of(MOD_ID, "horse_whistle"));

    @Override
    public void onInitialize() {
        // Create settings with registry key and register item
        Item.Settings settings = new Item.Settings().registryKey(HORSE_WHISTLE_KEY);
        HORSE_WHISTLE = Registry.register(
            Registries.ITEM,
            HORSE_WHISTLE_KEY,
            new HorseWhistleItem(settings)
        );
        
        ModEvents.register();
    }
}