package com.proxi.whistle.component;

import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import java.util.UUID;

public record BoundHorseData(UUID uuid, Identifier dimension, BlockPos pos) {}