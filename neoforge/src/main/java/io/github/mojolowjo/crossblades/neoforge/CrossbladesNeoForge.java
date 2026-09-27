package io.github.mojolowjo.crossblades.neoforge;

import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.network.AttackPayload;
import io.github.mojolowjo.crossblades.network.FeedbackPayload;
import io.github.mojolowjo.crossblades.network.GuardPayload;
import io.github.mojolowjo.crossblades.network.StatePayload;
import io.github.mojolowjo.crossblades.server.CombatServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(Crossblades.MOD_ID)
public class CrossbladesNeoForge {
    public CrossbladesNeoForge(IEventBus modBus) {
        Crossblades.init();

        modBus.addListener(RegisterPayloadHandlersEvent.class, CrossbladesNeoForge::registerPayloads);

        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> CombatServer.tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class, event -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                CombatServer.onPlayerLeave(player);
            }
        });
        NeoForge.EVENT_BUS.addListener(PlayerEvent.StartTracking.class, event -> {
            if (event.getEntity() instanceof ServerPlayer watcher) {
                CombatServer.onStartTracking(event.getTarget(), watcher);
            }
        });
        NeoForge.EVENT_BUS.addListener(AttackEntityEvent.class, event -> {
            if (!event.getEntity().level().isClientSide()
                    && CombatServer.shouldCancelVanillaAttack(event.getEntity(), event.getTarget())) {
                event.setCanceled(true);
            }
        });
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(AttackPayload.TYPE, AttackPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                CombatServer.onAttack(payload, player);
            }
        });
        registrar.playToServer(GuardPayload.TYPE, GuardPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                CombatServer.onGuard(payload, player);
            }
        });
        // Client handlers are registered in CrossbladesNeoForgeClient so servers never load client code.
        registrar.playToClient(StatePayload.TYPE, StatePayload.CODEC);
        registrar.playToClient(FeedbackPayload.TYPE, FeedbackPayload.CODEC);
    }
}
