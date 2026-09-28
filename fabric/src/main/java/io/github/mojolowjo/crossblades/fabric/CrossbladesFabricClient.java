package io.github.mojolowjo.crossblades.fabric;

import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.client.CombatAnimations;
import io.github.mojolowjo.crossblades.client.CombatClient;
import io.github.mojolowjo.crossblades.client.CombatHud;
import io.github.mojolowjo.crossblades.client.CombatSelfTest;
import io.github.mojolowjo.crossblades.network.FeedbackPayload;
import io.github.mojolowjo.crossblades.network.SettingsPayload;
import io.github.mojolowjo.crossblades.network.StatePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

public class CrossbladesFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        CombatClient.init();
        KeyMappingHelper.registerKeyMapping(CombatClient.TOGGLE_STANCE);

        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE, (payload, context) -> CombatClient.onState(payload));
        ClientPlayNetworking.registerGlobalReceiver(FeedbackPayload.TYPE, (payload, context) -> CombatClient.onFeedback(payload));
        ClientPlayNetworking.registerGlobalReceiver(SettingsPayload.TYPE, (payload, context) -> CombatClient.onSettings(payload));

        ClientTickEvents.START_CLIENT_TICK.register(CombatClient::onClientTickStart);
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> CombatClient.onDisconnect());

        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Crossblades.id("combat_hud"), CombatHud::render);

        CombatAnimations.register();

        if (CombatSelfTest.ENABLED) {
            ClientTickEvents.END_CLIENT_TICK.register(CombatSelfTest::onClientTickEnd);
        }
    }
}
