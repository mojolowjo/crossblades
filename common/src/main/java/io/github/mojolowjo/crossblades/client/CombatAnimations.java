package io.github.mojolowjo.crossblades.client;

import com.zigythebird.playeranim.animation.PlayerAnimResources;
import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranimcore.animation.Animation;
import com.zigythebird.playeranimcore.animation.RawAnimation;
import com.zigythebird.playeranimcore.animation.layered.IAnimation;
import com.zigythebird.playeranimcore.animation.layered.modifier.AbstractFadeModifier;
import com.zigythebird.playeranimcore.animation.layered.modifier.AbstractModifier;
import com.zigythebird.playeranimcore.animation.layered.modifier.SpeedModifier;
import com.zigythebird.playeranimcore.api.firstPerson.FirstPersonConfiguration;
import com.zigythebird.playeranimcore.api.firstPerson.FirstPersonMode;
import com.zigythebird.playeranimcore.easing.EasingType;
import com.zigythebird.playeranimcore.enums.PlayState;
import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.core.AttackDir;
import io.github.mojolowjo.crossblades.core.FighterState;
import io.github.mojolowjo.crossblades.core.Guard;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;

/**
 * Plays the combat animations on players through Player Animation Library, on its own layer so it
 * doesn't fight other mods' animations. The animation files live in
 * {@code assets/crossblades/player_animations} and are made by {@code tools/gen_animations.py}.
 */
public final class CombatAnimations {
    /** Above emotes (1000), as the docs recommend for gameplay animations. */
    private static final int LAYER_PRIORITY = 1600;
    private static final Identifier LAYER = Crossblades.id("combat");
    private static final int FADE_TICKS = 2;

    private CombatAnimations() {
    }

    /** Fabric: from the client entry point. NeoForge: inside FMLClientSetupEvent#enqueueWork. */
    public static void register() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, LAYER_PRIORITY, avatar -> {
            PlayerAnimationController controller = new PlayerAnimationController(avatar,
                    (animationController, state, animSetter) -> PlayState.STOP);
            controller.addModifierBefore(new SpeedModifier(1.0F));
            if (Crossblades.settings().client.firstPersonAnimations) {
                controller.setFirstPersonMode(FirstPersonMode.THIRD_PERSON_MODEL);
                controller.setFirstPersonConfiguration(new FirstPersonConfiguration()
                        .setShowRightArm(true)
                        .setShowLeftArm(false)
                        .setShowRightItem(true)
                        .setShowLeftItem(false));
            }
            return controller;
        });
    }

    /** Shows {@code shown} on {@code player}, stretched to last {@code ticks} ticks where that matters. */
    public static void play(Player player, FighterState.Shown shown, byte dir, int ticks) {
        PlayerAnimationController controller = controller(player);
        if (controller == null) {
            return;
        }
        switch (shown) {
            case WINDUP -> start(controller, "windup_" + attackName(AttackDir.byId(dir)), Animation.LoopType.HOLD_ON_LAST_FRAME, ticks);
            case STRIKE -> start(controller, "strike_" + attackName(AttackDir.byId(dir)), Animation.LoopType.PLAY_ONCE, ticks);
            case GUARD -> start(controller, "guard_" + guardName(Guard.byId(dir)), Animation.LoopType.HOLD_ON_LAST_FRAME, 0);
            case STAGGER, FLINCH -> start(controller, "stagger", Animation.LoopType.PLAY_ONCE, ticks);
            case IDLE -> start(controller, "rest", Animation.LoopType.PLAY_ONCE, 0);
        }
    }

    private static void start(PlayerAnimationController controller, String name, Animation.LoopType loop, int ticks) {
        Animation animation = PlayerAnimResources.getAnimation(Crossblades.id(name));
        if (animation == null) {
            Crossblades.LOG.warn("Missing animation {}", name);
            return;
        }
        float speed = 1.0F;
        if (ticks > 0 && animation.length() > 0) {
            speed = animation.length() / ticks;
        }
        for (AbstractModifier modifier : controller.getModifiers()) {
            if (modifier instanceof SpeedModifier speedModifier) {
                speedModifier.speed = speed;
            }
        }
        controller.replaceAnimationWithFade(
                AbstractFadeModifier.standardFadeIn(FADE_TICKS, EasingType.EASE_IN_OUT_SINE),
                RawAnimation.begin().then(animation, loop));
    }

    private static PlayerAnimationController controller(Player player) {
        IAnimation layer;
        try {
            layer = PlayerAnimationAccess.getPlayerAnimationLayer(player, LAYER);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return layer instanceof PlayerAnimationController controller ? controller : null;
    }

    private static String attackName(AttackDir dir) {
        return switch (dir) {
            case OVERHEAD -> "overhead";
            case LEFT -> "left";
            case RIGHT -> "right";
            case POKE -> "poke";
        };
    }

    private static String guardName(Guard guard) {
        return switch (guard) {
            case UP -> "up";
            case LEFT -> "left";
            case RIGHT -> "right";
        };
    }
}
