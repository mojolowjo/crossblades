package io.github.mojolowjo.crossblades.network;

import io.github.mojolowjo.crossblades.Crossblades;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: block is held (or not) and the guard points this way. */
public record GuardPayload(boolean held, byte dir) implements CustomPacketPayload {
    public static final Type<GuardPayload> TYPE = new Type<>(Crossblades.id("guard"));
    public static final StreamCodec<ByteBuf, GuardPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, GuardPayload::held,
            ByteBufCodecs.BYTE, GuardPayload::dir,
            GuardPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
