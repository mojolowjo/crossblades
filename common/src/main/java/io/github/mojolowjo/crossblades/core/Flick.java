package io.github.mojolowjo.crossblades.core;

/** A quick mouse movement in one of four screen directions. */
public enum Flick {
    UP,
    DOWN,
    LEFT,
    RIGHT;

    public Flick opposite() {
        return switch (this) {
            case UP -> DOWN;
            case DOWN -> UP;
            case LEFT -> RIGHT;
            case RIGHT -> LEFT;
        };
    }
}
