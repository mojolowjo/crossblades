package io.github.mojolowjo.crossblades.fabric;

import io.github.mojolowjo.crossblades.platform.IPlatformHelper;
import java.nio.file.Path;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

public class FabricPlatformHelper implements IPlatformHelper {
    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        FabricClientAccess.sendToServer(payload);
    }

    @Override
    public boolean canSendToServer(CustomPacketPayload.Type<?> type) {
        return FabricClientAccess.canSendToServer(type);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        // Players without the mod can still join a Fabric server; just don't send them anything.
        if (ServerPlayNetworking.canSend(player, payload.type())) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    @Override
    public void sendToTrackingAndSelf(Entity entity, CustomPacketPayload payload) {
        for (ServerPlayer watcher : PlayerLookup.tracking(entity)) {
            sendToPlayer(watcher, payload);
        }
        if (entity instanceof ServerPlayer self) {
            sendToPlayer(self, payload);
        }
    }
}
