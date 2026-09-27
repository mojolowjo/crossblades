package io.github.mojolowjo.crossblades.network;

import io.github.mojolowjo.crossblades.Crossblades;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: "I clicked attack with this direction loaded." */
public record AttackPayload(byte dir) implements CustomPacketPayload {
    public static final Type<AttackPayload> TYPE = new Type<>(Crossblades.id("attack"));
    public static final StreamCodec<ByteBuf, AttackPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.BYTE, AttackPayload::dir, AttackPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
