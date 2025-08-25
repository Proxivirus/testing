package com.proxi.whistle.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.component.ComponentType;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import java.util.UUID;
import static com.proxi.whistle.WhistleMod.MOD_ID;

public class ModDataComponents {
    public static final ComponentType<BoundHorseData> BOUND_HORSE_DATA = Registry.register(
        Registries.DATA_COMPONENT_TYPE,
        Identifier.of(MOD_ID, "bound_horse_data"),
        ComponentType.<BoundHorseData>builder()
            .codec(
                RecordCodecBuilder.create(instance -> 
                    instance.group(
                        Codec.STRING.xmap(UUID::fromString, UUID::toString).fieldOf("uuid").forGetter(BoundHorseData::uuid),
                        Identifier.CODEC.fieldOf("dimension").forGetter(BoundHorseData::dimension),
                        BlockPos.CODEC.fieldOf("pos").forGetter(BoundHorseData::pos)
                    ).apply(instance, BoundHorseData::new)
                )
            )
            .packetCodec(
                PacketCodec.tuple(
                    PacketCodecs.STRING.xmap(UUID::fromString, UUID::toString),
                    BoundHorseData::uuid,
                    Identifier.PACKET_CODEC,
                    BoundHorseData::dimension,
                    BlockPos.PACKET_CODEC,
                    BoundHorseData::pos,
                    BoundHorseData::new
                )
            )
            .build()
    );

    public static void initialize() {}
}