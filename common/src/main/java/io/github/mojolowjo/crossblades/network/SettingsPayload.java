package io.github.mojolowjo.crossblades.network;

import io.github.mojolowjo.crossblades.Crossblades;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the server's combat rules as JSON (without the client section). Sent when a
 * player joins and whenever {@code /crossblades} changes something, so everyone's game predicts
 * the same timings and reads flicks the same way.
 */
public record SettingsPayload(String json) implements CustomPacketPayload {
    public static final Type<SettingsPayload> TYPE = new Type<>(Crossblades.id("settings"));
    public static final StreamCodec<ByteBuf, SettingsPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SettingsPayload::json,
            SettingsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
