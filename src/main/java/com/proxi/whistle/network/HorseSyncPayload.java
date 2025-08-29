package com.proxi.whistle.network;

import com.proxi.whistle.WhistleMod;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Custom payload for sending {slot, uuid, dimension, pos} from server -> client.
 */
public record HorseSyncPayload(int slot, UUID uuid, Identifier dimension, BlockPos pos) implements CustomPayload {
    // Use the same Identifier you declared in WhistleMod (HORSE_SYNC_PACKET).
    public static final CustomPayload.Id<HorseSyncPayload> ID = new CustomPayload.Id<>(WhistleMod.HORSE_SYNC_PACKET);

    // PacketCodec: encoder is (HorseSyncPayload, PacketByteBuf) and decoder is (PacketByteBuf -> HorseSyncPayload)
    public static final PacketCodec<PacketByteBuf, HorseSyncPayload> CODEC =
        PacketCodec.of(HorseSyncPayload::write, HorseSyncPayload::new);

    // Decoder constructor: read fields in same order as write
    public HorseSyncPayload(PacketByteBuf buf) {
        this(buf.readVarInt(), buf.readUuid(), buf.readIdentifier(), buf.readBlockPos());
    }

    // Encoder: write fields in stable order
    private void write(PacketByteBuf buf) {
        buf.writeVarInt(this.slot);
        buf.writeUuid(this.uuid);
        buf.writeIdentifier(this.dimension);
        buf.writeBlockPos(this.pos);
    }

    @Override
    public CustomPayload.Id<? extends CustomPayload> getId() {
        return ID;
    }
}
