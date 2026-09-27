package io.github.mojolowjo.crossblades.neoforge;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** Client-only networking, kept in its own class so dedicated servers never load it. */
final class NeoForgeClientAccess {
    private NeoForgeClientAccess() {
    }

    static void sendToServer(CustomPacketPayload payload) {
        if (Minecraft.getInstance().getConnection() != null) {
            ClientPacketDistributor.sendToServer(payload);
        }
    }

    static InputConstants.Key boundKey(KeyMapping mapping) {
        return mapping.getKey();
    }

    static boolean canSendToServer(CustomPacketPayload.Type<?> type) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(type);
    }
}
