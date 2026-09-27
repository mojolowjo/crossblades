package io.github.mojolowjo.crossblades.neoforge;

import io.github.mojolowjo.crossblades.platform.IPlatformHelper;
import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.network.PacketDistributor;

public class NeoForgePlatformHelper implements IPlatformHelper {
    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        NeoForgeClientAccess.sendToServer(payload);
    }

    @Override
    public boolean canSendToServer(CustomPacketPayload.Type<?> type) {
        return NeoForgeClientAccess.canSendToServer(type);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    @Override
    public void sendToTrackingAndSelf(Entity entity, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(entity, payload);
    }

    @Override
    public boolean isFakePlayer(ServerPlayer player) {
        return player.isFakePlayer();
    }

    @Override
    public ServerPlayer createFakePlayer(ServerLevel level, String name) {
        return FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name));
    }

    @Override
    public InputConstants.Key boundKey(KeyMapping mapping) {
        return NeoForgeClientAccess.boundKey(mapping);
    }
}
