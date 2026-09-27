package io.github.mojolowjo.crossblades.core;

/**
 * One player's combat state on the server, advanced in game ticks. Plain Java so the rules can be
 * tested without Minecraft.
 *
 * <pre>
 * IDLE --click--> WINDUP --wind-up ends--> (strike) RECOVERY --> IDLE
 *                   |  hit clean while winding up      --> RECOVERY (flinch)
 *                   |  your strike is parried          --> STAGGER
 * Guard only counts while IDLE (and holding a weapon).
 * </pre>
 */
public final class FighterState {
    public enum Phase {
        IDLE,
        WINDUP,
        RECOVERY,
        STAGGER
    }

    /** What the clients should be shown (animations). Only the latest change per tick is sent. */
    public enum Shown {
        IDLE,
        WINDUP,
        STRIKE,
        GUARD,
        STAGGER,
        FLINCH;

        private static final Shown[] VALUES = values();

        public byte id() {
            return (byte) ordinal();
        }

        public static Shown byId(int id) {
            return id >= 0 && id < VALUES.length ? VALUES[id] : IDLE;
        }
    }

    private Phase phase = Phase.IDLE;
    private long phaseEnd;

    private AttackDir attackDir = AttackDir.POKE;
    private long attackStart = Long.MIN_VALUE;
    private AttackDir bufferedAttack;

    private boolean guardHeld;
    private Guard guardDir = Guard.RIGHT;
    private boolean guardActive;
    private long guardSince;

    private boolean hasWeapon = true;

    private Shown pendingShown;
    private byte pendingDir;
    private int pendingTicks;

    public Phase phase() {
        return phase;
    }

    public long phaseEnd() {
        return phaseEnd;
    }

    public AttackDir attackDir() {
        return attackDir;
    }

    public long attackStart() {
        return attackStart;
    }

    public boolean guardActive() {
        return guardActive;
    }

    public Guard guardDir() {
        return guardDir;
    }

    public long guardSince() {
        return guardSince;
    }

    public boolean isWindingUp() {
        return phase == Phase.WINDUP;
    }

    /** True when the wind-up is over and the swing should hit this tick. */
    public boolean strikeDue(long now) {
        return phase == Phase.WINDUP && now >= phaseEnd;
    }

    /**
     * The player clicked attack.
     *
     * @return true if the attack started (or was queued) and false if it was refused
     */
    public boolean requestAttack(AttackDir dir, long now, CombatSettings settings) {
        if (!hasWeapon) {
            return false;
        }
        if (phase == Phase.IDLE) {
            startWindup(dir, now, settings);
            return true;
        }
        long bufferTicks = Math.round(settings.inputBufferMs / (float) CombatSettings.MS_PER_TICK);
        if ((phase == Phase.RECOVERY || phase == Phase.STAGGER) && phaseEnd - now <= bufferTicks) {
            bufferedAttack = dir;
            return true;
        }
        return false;
    }

    private void startWindup(AttackDir dir, long now, CombatSettings settings) {
        int windup = CombatSettings.ticks(settings.attack(dir).windupMs);
        phase = Phase.WINDUP;
        attackDir = dir;
        attackStart = now;
        phaseEnd = now + windup;
        bufferedAttack = null;
        guardActive = false;
        show(Shown.WINDUP, dir.id(), windup);
    }

    /** The player pressed or released block, or flicked to a new guard direction while holding it. */
    public void setGuard(boolean held, Guard dir, long now) {
        boolean dirChanged = dir != guardDir;
        guardHeld = held;
        guardDir = dir;
        if (guardActive && !held) {
            guardActive = false;
            show(Shown.IDLE, (byte) 0, 0);
        } else if (guardActive && dirChanged) {
            // Changing direction restarts the block timing: the timing is "how long you've
            // been guarding the side the hit arrives on".
            guardSince = now;
            show(Shown.GUARD, dir.id(), 0);
        }
        updateGuard(now);
    }

    /**
     * Advance to {@code now}. Call once per tick before strikes are resolved.
     *
     * @param holdingWeapon whether the player currently holds a combat weapon
     */
    public void tick(long now, boolean holdingWeapon, CombatSettings settings) {
        hasWeapon = holdingWeapon;
        if (!holdingWeapon) {
            bufferedAttack = null;
            if (phase == Phase.WINDUP) {
                // Switched away from the weapon mid-swing: the swing is cancelled.
                phase = Phase.IDLE;
                show(Shown.IDLE, (byte) 0, 0);
            }
        }
        if ((phase == Phase.RECOVERY || phase == Phase.STAGGER) && now >= phaseEnd) {
            phase = Phase.IDLE;
            if (bufferedAttack != null && hasWeapon) {
                startWindup(bufferedAttack, now, settings);
            } else {
                show(Shown.IDLE, (byte) 0, 0);
            }
        }
        updateGuard(now);
    }

    private void updateGuard(long now) {
        boolean shouldGuard = guardHeld && hasWeapon && phase == Phase.IDLE;
        if (shouldGuard && !guardActive) {
            guardActive = true;
            guardSince = now;
            show(Shown.GUARD, guardDir.id(), 0);
        } else if (!shouldGuard && guardActive) {
            guardActive = false;
            if (phase == Phase.IDLE) {
                show(Shown.IDLE, (byte) 0, 0);
            }
        }
    }

    /** The wind-up finished and the swing was resolved. */
    public void finishStrike(long now, CombatSettings settings) {
        int recovery = CombatSettings.ticks(settings.attack(attackDir).recoveryMs);
        phase = Phase.RECOVERY;
        phaseEnd = now + recovery;
        show(Shown.STRIKE, attackDir.id(), recovery);
    }

    /** Your swing was parried: you are stunned and cannot attack or block. */
    public void stagger(long now, CombatSettings settings) {
        int ticks = CombatSettings.ticks(settings.parryStaggerMs);
        phase = Phase.STAGGER;
        phaseEnd = now + ticks;
        bufferedAttack = null;
        guardActive = false;
        show(Shown.STAGGER, attackDir.id(), ticks);
    }

    /** You took a clean hit while winding up: your attack is cancelled. */
    public void flinch(long now, CombatSettings settings) {
        if (phase != Phase.WINDUP) {
            return;
        }
        int ticks = CombatSettings.ticks(settings.hitFlinchMs);
        phase = Phase.RECOVERY;
        phaseEnd = now + ticks;
        bufferedAttack = null;
        show(Shown.FLINCH, attackDir.id(), ticks);
    }

    /** You parried: your own attack turns into a fast riposte. */
    public void riposte(long now, CombatSettings settings) {
        if (phase != Phase.WINDUP) {
            return;
        }
        long end = now + CombatSettings.ticks(settings.riposteWindupMs);
        if (end < phaseEnd) {
            phaseEnd = end;
            show(Shown.WINDUP, attackDir.id(), (int) (phaseEnd - now));
        }
    }

    private void show(Shown shown, byte dir, int ticks) {
        pendingShown = shown;
        pendingDir = dir;
        pendingTicks = ticks;
    }

    /** Whether something changed since the last {@link #clearShown()}. */
    public boolean hasShown() {
        return pendingShown != null;
    }

    public Shown shown() {
        return pendingShown;
    }

    public byte shownDir() {
        return pendingDir;
    }

    public int shownTicks() {
        return pendingTicks;
    }

    public void clearShown() {
        pendingShown = null;
    }

    /** What an observer joining now should see. */
    public Shown currentlyShown() {
        return switch (phase) {
            case WINDUP -> Shown.WINDUP;
            case RECOVERY -> Shown.IDLE;
            case STAGGER -> Shown.STAGGER;
            case IDLE -> guardActive ? Shown.GUARD : Shown.IDLE;
        };
    }
}
