package io.github.mojolowjo.crossblades.core;

/** A guard direction, from the defender's own point of view. */
public enum Guard {
    UP,
    LEFT,
    RIGHT;

    private static final Guard[] VALUES = values();

    public byte id() {
        return (byte) ordinal();
    }

    public static Guard byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : RIGHT;
    }

    /** The guard set by a mouse flick. A flick down loads a poke and leaves the guard alone. */
    public static Guard fromFlick(Flick flick, Guard current) {
        return switch (flick) {
            case UP -> UP;
            case LEFT -> LEFT;
            case RIGHT -> RIGHT;
            case DOWN -> current;
        };
    }
}
