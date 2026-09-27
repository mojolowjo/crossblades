package io.github.mojolowjo.crossblades.platform;

import java.nio.file.Path;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** The few things that work differently on Fabric and NeoForge. */
public interface IPlatformHelper {
    String getPlatformName();

    Path getConfigDir();

    /** Client only. */
    void sendToServer(CustomPacketPayload payload);

    /** Client only: whether the server we're connected to understands this payload (has the mod). */
    boolean canSendToServer(CustomPacketPayload.Type<?> type);

    void sendToPlayer(ServerPlayer player, CustomPacketPayload payload);

    /** Sends to everyone who can see {@code entity}, and to the entity itself if it is a player. */
    void sendToTrackingAndSelf(Entity entity, CustomPacketPayload payload);
}
