package net.whistlemod;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;
import net.whistlemod.world.summoning.SummonDimensionHandling;

@Config(name = "whistlemod")
public class ModConfig implements ConfigData {
    @ConfigEntry.Gui.Tooltip
    public int whistleMaxDistance = 10000;

    @ConfigEntry.Gui.EnumHandler(option = ConfigEntry.Gui.EnumHandler.EnumDisplayOption.BUTTON)
    public SummonDimensionHandling whistleDimensionHandling = SummonDimensionHandling.SAME;

    @ConfigEntry.Gui.Tooltip
    public String[] whistleDimensions = new String[0];
}