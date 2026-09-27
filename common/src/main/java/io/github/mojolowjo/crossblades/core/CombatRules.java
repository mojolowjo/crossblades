package io.github.mojolowjo.crossblades.core;

/** Decides what happens when a swing reaches a defender. */
public final class CombatRules {
    private CombatRules() {
    }

    public enum Kind {
        /** Clean hit: full damage. */
        HIT,
        /** Blocked in the right direction but not perfectly timed: some damage gets through. */
        BLOCK,
        /** Blocked in the right direction with perfect timing: no damage. */
        PERFECT_BLOCK,
        /** The defender attacked into the swing in the right direction at the right moment. */
        PARRY
    }

    /**
     * @param damageTaken share of the swing's damage the defender takes, 0 to 1
     * @param msBeforeHit for blocks and parries, how long before the hit the defender acted
     */
    public record Outcome(Kind kind, double damageTaken, long msBeforeHit) {
        public static final Outcome CLEAN_HIT = new Outcome(Kind.HIT, 1.0, -1);
    }

    /**
     * @param incoming the attacker's swing direction
     * @param defender the defender's combat state, or {@code null} if the defender is not a player
     * @param defenderFacesAttacker whether the attacker is inside the defender's block cone
     * @param now the tick the swing hits
     */
    public static Outcome resolve(AttackDir incoming, FighterState defender, boolean defenderFacesAttacker,
                                  long now, CombatSettings settings) {
        if (defender == null || !defenderFacesAttacker) {
            return Outcome.CLEAN_HIT;
        }

        // Parry: the defender started an attack toward the side the swing comes from, just in time.
        if (defender.isWindingUp() && incoming.isParriedBy(defender.attackDir())) {
            long ms = (now - defender.attackStart()) * CombatSettings.MS_PER_TICK;
            if (ms >= 0 && ms < settings.parryWindowMs) {
                return new Outcome(Kind.PARRY, 0.0, ms);
            }
        }

        if (defender.guardActive() && incoming.isStoppedBy(defender.guardDir())) {
            long ms = (now - defender.guardSince()) * CombatSettings.MS_PER_TICK;
            double taken = settings.blockDamageTaken(ms);
            return new Outcome(taken <= 0 ? Kind.PERFECT_BLOCK : Kind.BLOCK, taken, ms);
        }

        return Outcome.CLEAN_HIT;
    }
}
