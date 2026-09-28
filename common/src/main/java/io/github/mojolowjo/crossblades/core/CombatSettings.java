package io.github.mojolowjo.crossblades.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Every tunable number in the mod. Saved as {@code config/crossblades.json}, and changeable while
 * playing with the {@code /crossblades} command.
 * <p>
 * On a server, the server's copy decides all combat rules, including how mouse flicks are read;
 * it is sent to every player who joins. The {@link Client} section only affects the player whose
 * game reads it (visuals).
 * <p>
 * Times are in milliseconds. The server runs in 50 ms ticks, so times are rounded to the
 * nearest 50 ms.
 */
public final class CombatSettings {
    public static final int MS_PER_TICK = 50;

    public Attack overhead = new Attack(600, 400, 1.3, 3.2, 1, 8, 40);
    public Attack slash = new Attack(450, 350, 1.0, 3.0, 3, 55, 15);
    public Attack poke = new Attack(350, 300, 0.8, 3.6, 1, 5, 5);

    /** How much damage a correct-direction block lets through, by how early block was pressed. */
    public List<BlockTier> blockTiers = defaultTiers();
    /** Damage let through when block was pressed earlier than the last tier. */
    public double blockDamageAfterLastTier = 0.8;

    /** A parry is an attack in the matching direction started this close to the enemy's hit. */
    public int parryWindowMs = 100;
    /** How long a parried attacker is stunned (cannot attack or block). */
    public int parryStaggerMs = 600;
    /** A parry turns your attack into a fast riposte: its remaining wind-up is cut to this. */
    public int riposteWindupMs = 150;

    /** Taking a clean hit while winding up cancels your attack. */
    public boolean hitsInterruptWindups = true;
    /** How long you can't act after your wind-up is interrupted. */
    public int hitFlinchMs = 300;

    /** You can only block attacks coming from within this angle of where you're looking (each side). */
    public double blockConeDegrees = 80;

    /** Stop normal click-spam attacks with weapons, so all weapon damage goes through this system. */
    public boolean disableVanillaWeaponAttacks = true;
    /** An attack clicked this close to the end of your recovery is queued instead of dropped. */
    public int inputBufferMs = 150;
    /** Extra size added around every hitbox, in blocks, to make hits a little forgiving. */
    public double hitboxPadding = 0.15;

    /** How quick mouse movements are turned into attack and guard directions. Same for everyone on a server. */
    public FlickSettings flick = new FlickSettings();

    public Client client = new Client();

    public Attack attack(AttackDir dir) {
        return switch (dir) {
            case OVERHEAD -> overhead;
            case LEFT, RIGHT -> slash;
            case POKE -> poke;
        };
    }

    /** Damage let through (0 to 1) for a correct-direction block pressed {@code msBeforeHit} before the hit. */
    public double blockDamageTaken(long msBeforeHit) {
        for (BlockTier tier : blockTiers) {
            if (msBeforeHit < tier.underMs) {
                return tier.damageTaken;
            }
        }
        return blockDamageAfterLastTier;
    }

    public static int ticks(int ms) {
        return Math.max(1, Math.round(ms / (float) MS_PER_TICK));
    }

    /** Clamps anything a hand-edited file could break. */
    public CombatSettings sanitize() {
        overhead = sanitize(overhead, new Attack(600, 400, 1.3, 3.2, 1, 8, 40));
        slash = sanitize(slash, new Attack(450, 350, 1.0, 3.0, 3, 55, 15));
        poke = sanitize(poke, new Attack(350, 300, 0.8, 3.6, 1, 5, 5));
        if (blockTiers == null) {
            blockTiers = defaultTiers();
        }
        List<BlockTier> cleaned = new ArrayList<>();
        for (BlockTier tier : blockTiers) {
            if (tier != null) {
                cleaned.add(new BlockTier(clampMs(tier.underMs, 0), clamp01(tier.damageTaken)));
            }
        }
        cleaned.sort((a, b) -> Integer.compare(a.underMs, b.underMs));
        blockTiers = cleaned;
        blockDamageAfterLastTier = clamp01(blockDamageAfterLastTier);
        parryWindowMs = clampMs(parryWindowMs, 0);
        parryStaggerMs = clampMs(parryStaggerMs, 0);
        riposteWindupMs = clampMs(riposteWindupMs, 0);
        hitFlinchMs = clampMs(hitFlinchMs, 0);
        blockConeDegrees = Math.max(0, Math.min(180, blockConeDegrees));
        inputBufferMs = clampMs(inputBufferMs, 0);
        hitboxPadding = Math.max(0, Math.min(2, hitboxPadding));
        if (flick == null) {
            flick = new FlickSettings();
        }
        flick.sanitize();
        if (client == null) {
            client = new Client();
        }
        return this;
    }

    private static Attack sanitize(Attack attack, Attack fallback) {
        if (attack == null) {
            return fallback;
        }
        attack.windupMs = clampMs(attack.windupMs, 50);
        attack.recoveryMs = clampMs(attack.recoveryMs, 0);
        attack.damageMultiplier = Double.isNaN(attack.damageMultiplier) ? 1 : Math.max(0, Math.min(100, attack.damageMultiplier));
        attack.reach = Math.max(0.5, Math.min(12, attack.reach));
        attack.maxTargets = Math.max(1, Math.min(32, attack.maxTargets));
        attack.horizontalArcDegrees = Math.max(0, Math.min(90, attack.horizontalArcDegrees));
        attack.verticalArcDegrees = Math.max(0, Math.min(90, attack.verticalArcDegrees));
        return attack;
    }

    /** Times are kept between {@code min} and 10 seconds. */
    private static int clampMs(int ms, int min) {
        return Math.max(min, Math.min(10_000, ms));
    }

    private static double clamp01(double v) {
        return Double.isNaN(v) ? 1 : Math.max(0, Math.min(1, v));
    }

    private static List<BlockTier> defaultTiers() {
        List<BlockTier> tiers = new ArrayList<>();
        tiers.add(new BlockTier(100, 0.0));
        tiers.add(new BlockTier(200, 0.2));
        tiers.add(new BlockTier(300, 0.4));
        tiers.add(new BlockTier(400, 0.6));
        return tiers;
    }

    public static final class Attack {
        /** Time from click to the moment the swing hits. This is the opponent's time to react. */
        public int windupMs;
        /** Time after the hit before you can attack or block again. */
        public int recoveryMs;
        /** Multiplies your normal attack damage (weapon + strength + enchantments). */
        public double damageMultiplier;
        /** How far the swing reaches, in blocks, from your eyes. */
        public double reach;
        /** How many enemies one swing can hit. */
        public int maxTargets;
        /** How wide the swing is, to each side of where you're looking. */
        public double horizontalArcDegrees;
        /** How tall the swing is, above and below where you're looking. */
        public double verticalArcDegrees;

        public Attack() {
        }

        public Attack(int windupMs, int recoveryMs, double damageMultiplier, double reach, int maxTargets,
                      double horizontalArcDegrees, double verticalArcDegrees) {
            this.windupMs = windupMs;
            this.recoveryMs = recoveryMs;
            this.damageMultiplier = damageMultiplier;
            this.reach = reach;
            this.maxTargets = maxTargets;
            this.horizontalArcDegrees = horizontalArcDegrees;
            this.verticalArcDegrees = verticalArcDegrees;
        }
    }

    public static final class BlockTier {
        /** This tier applies when block was pressed less than this many ms before the hit. */
        public int underMs;
        /** Share of the damage you still take: 0 = none, 1 = all of it. */
        public double damageTaken;

        public BlockTier() {
        }

        public BlockTier(int underMs, double damageTaken) {
            this.underMs = underMs;
            this.damageTaken = damageTaken;
        }
    }

    /** How mouse flicks are read. */
    public static final class FlickSettings {
        /** How far (in degrees of camera turn) a quick mouse movement must go to count as a flick. */
        public double thresholdDegrees = 15;
        /** The flick has to happen within this much time. */
        public int windowMs = 100;
        /** The flick's main direction must be this many times bigger than the other direction. */
        public double dominance = 1.5;
        /**
         * After a flick, moving back the opposite way within this time is treated as re-aiming
         * and ignored, so flicking right and then re-centering on your target keeps "right".
         */
        public int returnGraceMs = 400;
        /** ...unless the opposite movement is this many times the normal threshold. */
        public double returnOverride = 2.0;

        void sanitize() {
            thresholdDegrees = Math.max(1, Math.min(180, thresholdDegrees));
            windowMs = Math.max(50, Math.min(1000, windowMs));
            dominance = Math.max(1, Math.min(10, dominance));
            returnGraceMs = Math.max(0, Math.min(5000, returnGraceMs));
            returnOverride = Math.max(1, Math.min(10, returnOverride));
        }
    }

    /** Settings that only affect your own game. */
    public static final class Client {
        /** Show your own swings in first person. Turn off if it looks wrong with other mods. */
        public boolean firstPersonAnimations = true;
        /** Show the arrow next to the crosshair. */
        public boolean showDirectionArrow = true;
        /** Show "PERFECT BLOCK", "PARRY!" and similar text under the crosshair. */
        public boolean showFeedbackText = true;
    }
}
