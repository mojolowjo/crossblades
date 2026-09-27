package io.github.mojolowjo.crossblades.core;

/**
 * The four attacks. Directions are always from the attacker's own point of view:
 * {@link #LEFT} means the weapon starts on the attacker's left side.
 */
public enum AttackDir {
    OVERHEAD,
    LEFT,
    RIGHT,
    POKE;

    private static final AttackDir[] VALUES = values();

    public byte id() {
        return (byte) ordinal();
    }

    /** Unknown ids fall back to {@link #POKE} so a bad packet can never crash anything. */
    public static AttackDir byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : POKE;
    }

    /**
     * The guard (on the defender's side) that stops this attack when defender and attacker face
     * each other. An attack from the attacker's right arrives on the defender's left, and so on.
     * A poke is stopped by either side guard, so this returns {@code null} for it; use
     * {@link #isStoppedBy(Guard)} instead of comparing directly.
     */
    public Guard guardThatStopsIt() {
        return switch (this) {
            case OVERHEAD -> Guard.UP;
            case LEFT -> Guard.RIGHT;
            case RIGHT -> Guard.LEFT;
            case POKE -> null;
        };
    }

    /** Whether a defender holding {@code guard} blocks this attack. */
    public boolean isStoppedBy(Guard guard) {
        if (guard == null) {
            return false;
        }
        if (this == POKE) {
            return guard == Guard.LEFT || guard == Guard.RIGHT;
        }
        return guardThatStopsIt() == guard;
    }

    /**
     * The guard this attack corresponds to when used as a parry: you parry by attacking toward the
     * side the enemy's weapon is coming from, which is the same side you would guard.
     * Pokes cannot parry, so this is {@code null} for them.
     */
    public Guard asGuard() {
        return switch (this) {
            case OVERHEAD -> Guard.UP;
            case LEFT -> Guard.LEFT;
            case RIGHT -> Guard.RIGHT;
            case POKE -> null;
        };
    }

    /** Whether a defender's attack in direction {@code defenderAttack} parries this incoming attack. */
    public boolean isParriedBy(AttackDir defenderAttack) {
        return defenderAttack != null && isStoppedBy(defenderAttack.asGuard());
    }

    /** The attack loaded by a mouse flick. */
    public static AttackDir fromFlick(Flick flick) {
        return switch (flick) {
            case UP -> OVERHEAD;
            case LEFT -> LEFT;
            case RIGHT -> RIGHT;
            case DOWN -> POKE;
        };
    }
}
