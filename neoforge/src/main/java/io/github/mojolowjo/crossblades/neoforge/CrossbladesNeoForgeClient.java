package io.github.mojolowjo.crossblades.neoforge;

import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.client.CombatAnimations;
import io.github.mojolowjo.crossblades.client.CombatClient;
import io.github.mojolowjo.crossblades.client.CombatHud;
import io.github.mojolowjo.crossblades.client.CombatSelfTest;
import io.github.mojolowjo.crossblades.network.FeedbackPayload;
import io.github.mojolowjo.crossblades.network.SettingsPayload;
import io.github.mojolowjo.crossblades.network.StatePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Crossblades.MOD_ID, dist = Dist.CLIENT)
public class CrossbladesNeoForgeClient {
    public CrossbladesNeoForgeClient(IEventBus modBus) {
        CombatClient.init();

        modBus.addListener(RegisterKeyMappingsEvent.class, event -> event.register(CombatClient.TOGGLE_STANCE));
        modBus.addListener(RegisterClientPayloadHandlersEvent.class, event -> {
            event.register(StatePayload.TYPE, (payload, context) -> CombatClient.onState(payload));
            event.register(FeedbackPayload.TYPE, (payload, context) -> CombatClient.onFeedback(payload));
            event.register(SettingsPayload.TYPE, (payload, context) -> CombatClient.onSettings(payload));
        });
        modBus.addListener(RegisterGuiLayersEvent.class,
                event -> event.registerAbove(VanillaGuiLayers.CROSSHAIR, Crossblades.id("combat_hud"), CombatHud::render));
        // Player Animation Library requires its layers to be registered on the main thread here.
        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(CombatAnimations::register));

        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, event -> CombatClient.onClientTickStart(Minecraft.getInstance()));
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> CombatClient.onDisconnect());

        if (CombatSelfTest.ENABLED) {
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> CombatSelfTest.onClientTickEnd(Minecraft.getInstance()));
        }
    }
}
