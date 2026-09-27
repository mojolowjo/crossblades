package io.github.mojolowjo.crossblades.fabric;

import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.network.AttackPayload;
import io.github.mojolowjo.crossblades.network.FeedbackPayload;
import io.github.mojolowjo.crossblades.network.GuardPayload;
import io.github.mojolowjo.crossblades.network.StatePayload;
import io.github.mojolowjo.crossblades.server.CombatServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.InteractionResult;

public class CrossbladesFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Crossblades.init();

        PayloadTypeRegistry.serverboundPlay().register(AttackPayload.TYPE, AttackPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(GuardPayload.TYPE, GuardPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StatePayload.TYPE, StatePayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(FeedbackPayload.TYPE, FeedbackPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(AttackPayload.TYPE,
                (payload, context) -> CombatServer.onAttack(payload, context.player()));
        ServerPlayNetworking.registerGlobalReceiver(GuardPayload.TYPE,
                (payload, context) -> CombatServer.onGuard(payload, context.player()));

        ServerTickEvents.END_SERVER_TICK.register(CombatServer::tick);
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> CombatServer.onPlayerLeave(listener.player));
        EntityTrackingEvents.START_TRACKING.register(CombatServer::onStartTracking);

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                !level.isClientSide() && CombatServer.shouldCancelVanillaAttack(player, entity)
                        ? InteractionResult.FAIL
                        : InteractionResult.PASS);
    }
}
