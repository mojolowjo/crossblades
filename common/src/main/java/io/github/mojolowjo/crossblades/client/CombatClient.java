package io.github.mojolowjo.crossblades.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.core.AttackDir;
import io.github.mojolowjo.crossblades.core.CombatSettings;
import io.github.mojolowjo.crossblades.core.FighterState;
import io.github.mojolowjo.crossblades.core.Flick;
import io.github.mojolowjo.crossblades.core.FlickDetector;
import io.github.mojolowjo.crossblades.core.Guard;
import io.github.mojolowjo.crossblades.network.AttackPayload;
import io.github.mojolowjo.crossblades.network.FeedbackPayload;
import io.github.mojolowjo.crossblades.network.GuardPayload;
import io.github.mojolowjo.crossblades.network.StatePayload;
import io.github.mojolowjo.crossblades.platform.Services;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Client side: reads mouse flicks and clicks, sends attacks and guards to the server, predicts
 * your own wind-up so it feels instant, and plays animations for everyone.
 */
public final class CombatClient {
    public static final KeyMapping TOGGLE_STANCE = new KeyMapping(
            "key.crossblades.toggle_stance", InputConstants.KEY_R, KeyMapping.Category.MISC);

    private static final FlickDetector FLICKS = new FlickDetector();

    private static boolean stanceOn = true;
    private static AttackDir loadedAttack = AttackDir.RIGHT;
    private static Guard guardDir = Guard.RIGHT;

    private static boolean sentGuardHeld;
    private static Guard sentGuardDir = Guard.RIGHT;

    /** Our own phase as far as the client knows (predicted, then corrected by the server). */
    private static FighterState.Phase localPhase = FighterState.Phase.IDLE;
    private static long localPhaseEnd;
    private static int localRecoveryTicks;
    private static long predictedAt = Long.MIN_VALUE;
    private static AttackDir predictedDir;

    private static long clientTicks;

    private static String feedbackText;
    private static int feedbackColor;
    private static long feedbackUntil;

    private CombatClient() {
    }

    /** Call from the loader's client entry point. */
    public static void init() {
        Crossblades.init();
        FLICKS.configure(Crossblades.settings().client);
    }

    // ---- state the HUD reads ----

    /** Whether directional combat is controlling your clicks right now. */
    public static boolean isActive() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        return stanceOn && player != null && player.isAlive() && !player.isSpectator()
                && Crossblades.isWeapon(player.getMainHandItem())
                && Services.PLATFORM.canSendToServer(AttackPayload.TYPE);
    }

    public static AttackDir loadedAttack() {
        return loadedAttack;
    }

    public static Guard guardDir() {
        return guardDir;
    }

    public static boolean isGuarding() {
        return sentGuardHeld && localPhase == FighterState.Phase.IDLE;
    }

    public static FighterState.Phase localPhase() {
        return localPhase;
    }

    public static String feedbackText() {
        return clientTicks < feedbackUntil ? feedbackText : null;
    }

    public static int feedbackColor() {
        return feedbackColor;
    }

    /** 1 while the text is fresh, fading to 0 as it expires. */
    public static float feedbackAlpha(float partialTick) {
        float left = feedbackUntil - clientTicks - partialTick;
        return Math.max(0, Math.min(1, left / 8.0F));
    }

    // ---- tick ----

    /** Runs at the start of every client tick, before vanilla handles the attack and use keys. */
    public static void onClientTickStart(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            resetConnectionState();
            return;
        }
        clientTicks++;

        while (TOGGLE_STANCE.consumeClick()) {
            stanceOn = !stanceOn;
            showFeedback(stanceOn ? "Combat stance ON" : "Combat stance OFF (tools mode)", 0xFFFFFFFF, 30);
        }

        if (localPhase != FighterState.Phase.IDLE && clientTicks >= localPhaseEnd) {
            if (localPhase == FighterState.Phase.WINDUP) {
                // Predicted: the server's "strike" message will correct this if it differs.
                localPhase = FighterState.Phase.RECOVERY;
                localPhaseEnd = clientTicks + localRecoveryTicks;
            } else {
                localPhase = FighterState.Phase.IDLE;
            }
        }

        boolean active = isActive();
        if (!active || mc.screen != null) {
            FLICKS.reset();
            sendGuard(false);
            return;
        }

        Flick flick = FLICKS.update(player.getYRot(), player.getXRot());
        if (flick != null) {
            loadedAttack = AttackDir.fromFlick(flick);
            guardDir = Guard.fromFlick(flick, guardDir);
        }

        // Take over the attack key while holding a weapon: every click is a directional swing,
        // and holding it does nothing (no block breaking or click-spam attacks).
        int clicks = 0;
        while (mc.options.keyAttack.consumeClick()) {
            clicks++;
        }
        mc.options.keyAttack.setDown(false);
        if (clicks > 0) {
            tryAttack(player);
        }

        sendGuard(mc.options.keyUse.isDown());
    }

    private static void tryAttack(LocalPlayer player) {
        if (localPhase == FighterState.Phase.WINDUP) {
            return;
        }
        Services.PLATFORM.sendToServer(new AttackPayload(loadedAttack.id()));
        if (localPhase == FighterState.Phase.IDLE) {
            // Predict the wind-up so the swing starts the moment you click. The server's reply
            // corrects the timing if its settings differ.
            CombatSettings.Attack attack = Crossblades.settings().attack(loadedAttack);
            int windup = CombatSettings.ticks(attack.windupMs);
            localPhase = FighterState.Phase.WINDUP;
            localPhaseEnd = clientTicks + windup;
            localRecoveryTicks = CombatSettings.ticks(attack.recoveryMs);
            predictedAt = clientTicks;
            predictedDir = loadedAttack;
            CombatAnimations.play(player, FighterState.Shown.WINDUP, loadedAttack.id(), windup);
        }
    }

    private static void sendGuard(boolean held) {
        if (held != sentGuardHeld || (held && guardDir != sentGuardDir)) {
            sentGuardHeld = held;
            sentGuardDir = guardDir;
            if (Minecraft.getInstance().getConnection() != null) {
                Services.PLATFORM.sendToServer(new GuardPayload(held, guardDir.id()));
            }
        }
    }

    // ---- packets from the server ----

    public static void onState(StatePayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = mc.level.getEntity(payload.entityId());
        if (!(entity instanceof Player player)) {
            return;
        }
        FighterState.Shown shown = FighterState.Shown.byId(payload.shown());
        int ticks = payload.ticks();

        if (player == mc.player) {
            boolean alreadyShowing = shown == FighterState.Shown.WINDUP
                    && predictedDir != null && predictedDir.id() == payload.dir()
                    && clientTicks - predictedAt <= 20;
            predictedDir = null;
            syncLocalPhase(shown, AttackDir.byId(payload.dir()), ticks);
            if (alreadyShowing) {
                return;
            }
        }
        CombatAnimations.play(player, shown, payload.dir(), ticks);
    }

    private static void syncLocalPhase(FighterState.Shown shown, AttackDir dir, int ticks) {
        switch (shown) {
            case WINDUP -> {
                localPhase = FighterState.Phase.WINDUP;
                localPhaseEnd = clientTicks + ticks;
                localRecoveryTicks = CombatSettings.ticks(Crossblades.settings().attack(dir).recoveryMs);
            }
            case STRIKE, FLINCH -> {
                localPhase = FighterState.Phase.RECOVERY;
                localPhaseEnd = clientTicks + ticks;
            }
            case STAGGER -> {
                localPhase = FighterState.Phase.STAGGER;
                localPhaseEnd = clientTicks + ticks;
            }
            case IDLE, GUARD -> localPhase = FighterState.Phase.IDLE;
        }
    }

    public static void onFeedback(FeedbackPayload payload) {
        int percent = payload.percent();
        switch (payload.kind()) {
            case FeedbackPayload.YOU_PERFECT_BLOCKED -> showFeedback("PERFECT BLOCK", 0xFFFFD84A, 20);
            case FeedbackPayload.YOU_BLOCKED -> showFeedback("Blocked - took " + percent + "%", 0xFF9FD7FF, 20);
            case FeedbackPayload.YOU_PARRIED -> showFeedback("PARRY!", 0xFF7CFF6B, 25);
            case FeedbackPayload.THEY_PERFECT_BLOCKED -> showFeedback("Perfectly blocked", 0xFFFF9A5C, 20);
            case FeedbackPayload.THEY_BLOCKED -> showFeedback("Blocked - dealt " + percent + "%", 0xFFFFC08A, 20);
            case FeedbackPayload.THEY_PARRIED -> showFeedback("PARRIED - staggered!", 0xFFFF5C5C, 25);
            case FeedbackPayload.YOU_WERE_INTERRUPTED -> showFeedback("Interrupted", 0xFFFF7A7A, 15);
            default -> {
            }
        }
    }

    private static void showFeedback(String text, int color, int ticks) {
        if (!Crossblades.settings().client.showFeedbackText) {
            return;
        }
        feedbackText = text;
        feedbackColor = color;
        feedbackUntil = clientTicks + ticks;
    }

    /** Called when leaving a world or server. */
    public static void resetConnectionState() {
        FLICKS.reset();
        sentGuardHeld = false;
        localPhase = FighterState.Phase.IDLE;
        predictedDir = null;
        feedbackUntil = 0;
    }
}
