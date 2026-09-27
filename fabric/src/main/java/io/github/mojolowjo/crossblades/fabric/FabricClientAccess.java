package io.github.mojolowjo.crossblades.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
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

    static InputConstants.Key boundKey(KeyMapping mapping) {
        return KeyMappingHelper.getBoundKeyOf(mapping);
    }

    static boolean canSendToServer(CustomPacketPayload.Type<?> type) {
        return Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(type);
    }
}
