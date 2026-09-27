package io.github.mojolowjo.crossblades.fabric;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client-only networking, kept in its own class so dedicated servers never load it. */
final class FabricClientAccess {
    private FabricClientAccess() {
    }

    static void sendToServer(CustomPacketPayload payload) {
        if (canSendToServer(payload.type())) {
            ClientPlayNetworking.send(payload);
        }
    }

    static boolean canSendToServer(CustomPacketPayload.Type<?> type) {
        return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(type);
    }
}
