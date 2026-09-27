package io.github.mojolowjo.crossblades.server;

import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.core.AttackDir;
import io.github.mojolowjo.crossblades.core.CombatRules;
import io.github.mojolowjo.crossblades.core.CombatSettings;
import io.github.mojolowjo.crossblades.core.FighterState;
import io.github.mojolowjo.crossblades.core.Guard;
import io.github.mojolowjo.crossblades.core.HitScan;
import io.github.mojolowjo.crossblades.network.AttackPayload;
import io.github.mojolowjo.crossblades.network.FeedbackPayload;
import io.github.mojolowjo.crossblades.network.GuardPayload;
import io.github.mojolowjo.crossblades.network.StatePayload;
import io.github.mojolowjo.crossblades.platform.Services;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The server side of directional combat: keeps every player's {@link FighterState}, advances it
 * each tick, resolves swings when their wind-up ends, and tells clients what to animate.
 * All methods run on the server thread.
 */
public final class CombatServer {
    private static final Map<UUID, FighterState> FIGHTERS = new HashMap<>();

    private CombatServer() {
    }

    private static CombatSettings settings() {
        return Crossblades.settings();
    }

    private static FighterState fighter(ServerPlayer player) {
        return FIGHTERS.computeIfAbsent(player.getUUID(), id -> new FighterState());
    }

    /**
     * The tick a packet belongs to. Packets are handled between ticks, so they count toward the
     * upcoming tick; this keeps packet times and {@link #tick} times on the same clock.
     */
    private static long now(ServerPlayer player) {
        MinecraftServer server = ((ServerLevel) player.level()).getServer();
        return server.getTickCount() + 1L;
    }

    private static boolean canFight(ServerPlayer player) {
        return player.isAlive() && !player.isSpectator() && Crossblades.isWeapon(player.getMainHandItem());
    }

    // ---- packets ----

    public static void onAttack(AttackPayload payload, ServerPlayer player) {
        FighterState fighter = fighter(player);
        long now = now(player);
        fighter.tick(now, canFight(player), settings());
        if (!fighter.requestAttack(AttackDir.byId(payload.dir()), now, settings())) {
            // Refused (still recovering, staggered, no weapon): re-sync the client.
            sendCurrentState(player, fighter, player);
        }
        broadcastIfChanged(player, fighter);
    }

    public static void onGuard(GuardPayload payload, ServerPlayer player) {
        FighterState fighter = fighter(player);
        long now = now(player);
        fighter.tick(now, canFight(player), settings());
        fighter.setGuard(payload.held(), Guard.byId(payload.dir()), now);
        broadcastIfChanged(player, fighter);
    }

    public static void onPlayerLeave(ServerPlayer player) {
        FIGHTERS.remove(player.getUUID());
    }

    /** A player started watching {@code target}: show them what it is doing right now. */
    public static void onStartTracking(Entity target, ServerPlayer watcher) {
        if (target instanceof ServerPlayer targetPlayer) {
            FighterState fighter = FIGHTERS.get(targetPlayer.getUUID());
            if (fighter != null && fighter.currentlyShown() != FighterState.Shown.IDLE) {
                sendCurrentState(targetPlayer, fighter, watcher);
            }
        }
    }

    /**
     * Stops normal click attacks with weapons, so all weapon damage goes through directional
     * combat. Returns true if the attack should be cancelled.
     */
    public static boolean shouldCancelVanillaAttack(Player player, Entity target) {
        return settings().disableVanillaWeaponAttacks
                && target instanceof LivingEntity
                && Crossblades.isWeapon(player.getMainHandItem());
    }

    // ---- tick ----

    public static void tick(MinecraftServer server) {
        if (FIGHTERS.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        CombatSettings settings = settings();

        List<ServerPlayer> strikers = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            FighterState fighter = FIGHTERS.get(player.getUUID());
            if (fighter == null) {
                continue;
            }
            fighter.tick(now, canFight(player), settings);
            if (fighter.strikeDue(now)) {
                strikers.add(player);
            }
        }

        // Resolve every swing that lands this tick before anyone's state changes, so two players
        // hitting each other on the same tick both land (a trade) no matter who is processed first.
        Set<UUID> strikerIds = new HashSet<>();
        for (ServerPlayer striker : strikers) {
            strikerIds.add(striker.getUUID());
        }
        Set<UUID> parried = new HashSet<>();
        Set<UUID> ripostes = new HashSet<>();
        Set<UUID> interrupted = new HashSet<>();
        for (ServerPlayer striker : strikers) {
            strike(striker, FIGHTERS.get(striker.getUUID()), now, settings, parried, ripostes, interrupted, strikerIds);
        }

        for (ServerPlayer striker : strikers) {
            FighterState fighter = FIGHTERS.get(striker.getUUID());
            if (parried.contains(striker.getUUID())) {
                fighter.stagger(now, settings);
            } else {
                fighter.finishStrike(now, settings);
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            FighterState fighter = FIGHTERS.get(player.getUUID());
            if (fighter == null) {
                continue;
            }
            if (ripostes.contains(player.getUUID())) {
                fighter.riposte(now, settings);
            } else if (interrupted.contains(player.getUUID()) && settings.hitsInterruptWindups && fighter.isWindingUp()) {
                fighter.flinch(now, settings);
                Services.PLATFORM.sendToPlayer(player, new FeedbackPayload(FeedbackPayload.YOU_WERE_INTERRUPTED, (byte) 0));
            }
            broadcastIfChanged(player, fighter);
        }
    }

    private static void strike(ServerPlayer attacker, FighterState attackerState, long now, CombatSettings settings,
                               Set<UUID> parried, Set<UUID> ripostes, Set<UUID> interrupted, Set<UUID> strikerIds) {
        ServerLevel level = (ServerLevel) attacker.level();
        AttackDir dir = attackerState.attackDir();
        CombatSettings.Attack attack = settings.attack(dir);
        Vec3 eye = attacker.getEyePosition();

        double searchRadius = attack.reach + 3;
        AABB searchBox = new AABB(eye.x - searchRadius, eye.y - searchRadius, eye.z - searchRadius,
                eye.x + searchRadius, eye.y + searchRadius, eye.z + searchRadius);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, searchBox,
                entity -> entity != attacker && entity.isAlive() && !entity.isSpectator()
                        && !attacker.isAlliedTo(entity) && attacker.getVehicle() != entity);

        List<HitScan.Target<LivingEntity>> targets = new ArrayList<>(nearby.size());
        for (LivingEntity entity : nearby) {
            AABB box = entity.getBoundingBox();
            targets.add(new HitScan.Target<>(entity,
                    new HitScan.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)));
        }
        List<HitScan.Hit<LivingEntity>> hits = HitScan.scan(eye.x, eye.y, eye.z, attacker.getYRot(), attacker.getXRot(),
                attack, settings.hitboxPadding, targets);

        level.playSound(null, attacker.getX(), attacker.getY(), attacker.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.5F, dir == AttackDir.POKE ? 1.4F : 0.9F);

        for (HitScan.Hit<LivingEntity> hit : hits) {
            LivingEntity target = hit.id();
            Vec3 hitPoint = new Vec3(hit.x(), hit.y(), hit.z());
            if (!hasLineOfSight(level, attacker, eye, hitPoint)) {
                continue;
            }

            FighterState defenderState = null;
            boolean facing = false;
            if (target instanceof ServerPlayer defender) {
                defenderState = FIGHTERS.get(defender.getUUID());
                facing = HitScan.inFrontCone(defender.getX(), defender.getZ(), defender.getYRot(),
                        attacker.getX(), attacker.getZ(), settings.blockConeDegrees);
            }
            CombatRules.Outcome outcome = CombatRules.resolve(dir, defenderState, facing, now, settings);
            applyOutcome(level, attacker, target, hitPoint, dir, attack, outcome, parried, ripostes, interrupted, strikerIds);
        }
    }

    private static boolean hasLineOfSight(ServerLevel level, ServerPlayer attacker, Vec3 from, Vec3 to) {
        BlockHitResult blocked = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, attacker));
        return blocked.getType() == HitResult.Type.MISS;
    }

    private static void applyOutcome(ServerLevel level, ServerPlayer attacker, LivingEntity target, Vec3 at, AttackDir dir,
                                     CombatSettings.Attack attack, CombatRules.Outcome outcome,
                                     Set<UUID> parried, Set<UUID> ripostes, Set<UUID> interrupted, Set<UUID> strikerIds) {
        ServerPlayer defender = target instanceof ServerPlayer player ? player : null;
        switch (outcome.kind()) {
            case PARRY -> {
                parried.add(attacker.getUUID());
                ripostes.add(target.getUUID());
                level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.6F, 1.5F);
                level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 12, 0.2, 0.2, 0.2, 0.3);
                feedback(defender, FeedbackPayload.YOU_PARRIED, 0);
                feedback(attacker, FeedbackPayload.THEY_PARRIED, 0);
            }
            case PERFECT_BLOCK -> {
                level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.4F, 1.9F);
                level.sendParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y, at.z, 8, 0.15, 0.15, 0.15, 0.2);
                feedback(defender, FeedbackPayload.YOU_PERFECT_BLOCKED, 0);
                feedback(attacker, FeedbackPayload.THEY_PERFECT_BLOCKED, 0);
            }
            case BLOCK -> {
                int percent = (int) Math.round(outcome.damageTaken() * 100);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BLOCK, SoundSource.PLAYERS, 0.8F, 1.1F);
                dealDamage(level, attacker, target, dir, attack, outcome.damageTaken());
                feedback(defender, FeedbackPayload.YOU_BLOCKED, percent);
                feedback(attacker, FeedbackPayload.THEY_BLOCKED, percent);
            }
            case HIT -> {
                boolean hurt = dealDamage(level, attacker, target, dir, attack, 1.0);
                if (hurt) {
                    level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 1.0F, 1.0F);
                    // A clean hit cancels the defender's own wind-up, unless they are swinging this same tick.
                    if (defender != null && !strikerIds.contains(defender.getUUID())) {
                        interrupted.add(defender.getUUID());
                    }
                }
            }
        }
    }

    /**
     * Deals {@code share} of the swing's full damage. Full damage is the attacker's attack damage
     * (weapon, strength, etc.) times the attack type's multiplier, plus enchantments like Sharpness.
     */
    private static boolean dealDamage(ServerLevel level, ServerPlayer attacker, LivingEntity target, AttackDir dir,
                                      CombatSettings.Attack attack, double share) {
        if (share <= 0) {
            return false;
        }
        ItemStack weapon = attacker.getMainHandItem();
        DamageSource source = level.damageSources().source(Crossblades.SWING_DAMAGE, attacker);
        float base = (float) attacker.getAttributeValue(Attributes.ATTACK_DAMAGE);
        float enchanted = EnchantmentHelper.modifyDamage(level, weapon, target, source, base);
        float damage = (float) (enchanted * attack.damageMultiplier * share);
        if (damage <= 0) {
            return false;
        }
        target.invulnerableTime = 0;
        boolean hurt = target.hurtServer(level, source, damage);
        if (hurt) {
            EnchantmentHelper.doPostAttackEffects(level, target, source);
            weapon.hurtAndBreak(1, attacker, EquipmentSlot.MAINHAND);
            attacker.causeFoodExhaustion(0.1F);
        }
        return hurt;
    }

    private static void feedback(ServerPlayer player, byte kind, int percent) {
        if (player != null) {
            Services.PLATFORM.sendToPlayer(player, new FeedbackPayload(kind, (byte) Math.max(0, Math.min(100, percent))));
        }
    }

    // ---- state sync ----

    private static void broadcastIfChanged(ServerPlayer player, FighterState fighter) {
        if (fighter.hasShown()) {
            Services.PLATFORM.sendToTrackingAndSelf(player, new StatePayload(player.getId(),
                    fighter.shown().id(), fighter.shownDir(), (short) Math.min(Short.MAX_VALUE, fighter.shownTicks())));
            fighter.clearShown();
        }
    }

    private static void sendCurrentState(ServerPlayer subject, FighterState fighter, ServerPlayer to) {
        FighterState.Shown shown = fighter.currentlyShown();
        byte dir = switch (shown) {
            case GUARD -> fighter.guardDir().id();
            case WINDUP, STAGGER -> fighter.attackDir().id();
            default -> 0;
        };
        long remaining = Math.max(0, fighter.phaseEnd() - now(subject));
        int ticks = shown == FighterState.Shown.WINDUP || shown == FighterState.Shown.STAGGER ? (int) remaining : 0;
        Services.PLATFORM.sendToPlayer(to, new StatePayload(subject.getId(), shown.id(), dir, (short) Math.min(Short.MAX_VALUE, ticks)));
    }
}
