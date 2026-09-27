package io.github.mojolowjo.crossblades.network;

import io.github.mojolowjo.crossblades.Crossblades;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to one client: text feedback about a clash ("PERFECT BLOCK", "PARRIED!", ...). */
public record FeedbackPayload(byte kind, byte percent) implements CustomPacketPayload {
    public static final Type<FeedbackPayload> TYPE = new Type<>(Crossblades.id("feedback"));
    public static final StreamCodec<ByteBuf, FeedbackPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BYTE, FeedbackPayload::kind,
            ByteBufCodecs.BYTE, FeedbackPayload::percent,
            FeedbackPayload::new);

    /** You blocked perfectly. */
    public static final byte YOU_PERFECT_BLOCKED = 0;
    /** You blocked; {@code percent} is the damage you took. */
    public static final byte YOU_BLOCKED = 1;
    /** You parried. */
    public static final byte YOU_PARRIED = 2;
    /** Your swing was perfectly blocked. */
    public static final byte THEY_PERFECT_BLOCKED = 3;
    /** Your swing was blocked; {@code percent} is the damage they took. */
    public static final byte THEY_BLOCKED = 4;
    /** Your swing was parried. */
    public static final byte THEY_PARRIED = 5;
    /** Your wind-up was interrupted by a hit. */
    public static final byte YOU_WERE_INTERRUPTED = 6;

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
