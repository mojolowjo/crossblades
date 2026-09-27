package io.github.mojolowjo.crossblades.network;

import io.github.mojolowjo.crossblades.Crossblades;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to clients: a player's visible combat state changed (for animations, and so the
 * player's own client stays in sync).
 *
 * @param shown  a {@link io.github.mojolowjo.crossblades.core.FighterState.Shown} id
 * @param dir    attack or guard direction id, depending on {@code shown}
 * @param ticks  how long this state lasts (wind-up, recovery, stagger), 0 if open-ended
 */
public record StatePayload(int entityId, byte shown, byte dir, short ticks) implements CustomPacketPayload {
    public static final Type<StatePayload> TYPE = new Type<>(Crossblades.id("state"));
    public static final StreamCodec<ByteBuf, StatePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, StatePayload::entityId,
            ByteBufCodecs.BYTE, StatePayload::shown,
            ByteBufCodecs.BYTE, StatePayload::dir,
            ByteBufCodecs.SHORT, StatePayload::ticks,
            StatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
