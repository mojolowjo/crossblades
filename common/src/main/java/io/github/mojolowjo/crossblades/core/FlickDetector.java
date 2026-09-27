package io.github.mojolowjo.crossblades.core;

/**
 * Turns camera rotation, sampled once per game tick, into {@link Flick}s.
 * <p>
 * A flick is a quick camera turn: within the last {@code windowTicks} ticks the camera turned
 * at least {@code thresholdDegrees} in one direction, and much more in that direction than
 * the other axis. After a flick, a movement back the opposite way within the grace period is
 * treated as re-aiming and ignored, unless it is much bigger than a normal flick.
 * <p>
 * Minecraft angles: yaw grows when turning right; pitch grows when looking down.
 */
public final class FlickDetector {
    private static final int MAX_WINDOW = 20;

    private final float[] yawDeltas = new float[MAX_WINDOW];
    private final float[] pitchDeltas = new float[MAX_WINDOW];
    private int head;
    private int filled;

    private boolean hasLast;
    private float lastYaw;
    private float lastPitch;

    private Flick lastFlick;
    private int ticksSinceFlick = Integer.MAX_VALUE;

    private double thresholdDegrees = 15;
    private int windowTicks = 2;
    private double dominance = 1.5;
    private int returnGraceTicks = 8;
    private double returnOverride = 2.0;

    public void configure(CombatSettings.Client settings) {
        this.thresholdDegrees = settings.flickThresholdDegrees;
        this.windowTicks = Math.max(1, Math.min(MAX_WINDOW, CombatSettings.ticks(settings.flickWindowMs)));
        this.dominance = settings.flickDominance;
        this.returnGraceTicks = Math.max(0, Math.round(settings.flickReturnGraceMs / (float) CombatSettings.MS_PER_TICK));
        this.returnOverride = settings.flickReturnOverride;
        clearWindow();
    }

    /** Forget all movement history, e.g. after a teleport, respawn or opening a screen. */
    public void reset() {
        hasLast = false;
        lastFlick = null;
        ticksSinceFlick = Integer.MAX_VALUE;
        clearWindow();
    }

    /**
     * Feed the camera angles for this tick.
     *
     * @return the flick that just finished, or {@code null}
     */
    public Flick update(float yaw, float pitch) {
        if (ticksSinceFlick != Integer.MAX_VALUE) {
            ticksSinceFlick++;
        }
        if (!hasLast) {
            hasLast = true;
            lastYaw = yaw;
            lastPitch = pitch;
            return null;
        }
        float dYaw = wrapDegrees(yaw - lastYaw);
        float dPitch = pitch - lastPitch;
        lastYaw = yaw;
        lastPitch = pitch;
        // A huge jump in one tick is a teleport or respawn, not a hand movement.
        if (Math.abs(dYaw) > 170 || Math.abs(dPitch) > 170) {
            clearWindow();
            return null;
        }
        push(dYaw, dPitch);

        double sumYaw = 0;
        double sumPitch = 0;
        int count = Math.min(filled, windowTicks);
        for (int i = 0; i < count; i++) {
            int index = Math.floorMod(head - 1 - i, MAX_WINDOW);
            sumYaw += yawDeltas[index];
            sumPitch += pitchDeltas[index];
        }

        Flick candidate = null;
        double magnitude = 0;
        double absYaw = Math.abs(sumYaw);
        double absPitch = Math.abs(sumPitch);
        if (absYaw >= thresholdDegrees && absYaw >= dominance * absPitch) {
            candidate = sumYaw > 0 ? Flick.RIGHT : Flick.LEFT;
            magnitude = absYaw;
        } else if (absPitch >= thresholdDegrees && absPitch >= dominance * absYaw) {
            candidate = sumPitch < 0 ? Flick.UP : Flick.DOWN;
            magnitude = absPitch;
        }
        if (candidate == null) {
            return null;
        }

        // Either way this movement has been used up.
        clearWindow();

        boolean isReturn = lastFlick != null
                && candidate == lastFlick.opposite()
                && ticksSinceFlick <= returnGraceTicks
                && magnitude < thresholdDegrees * returnOverride;
        if (isReturn) {
            // Re-aiming after a flick: keep the old direction, and don't treat a second
            // return movement as a return too.
            lastFlick = null;
            return null;
        }
        lastFlick = candidate;
        ticksSinceFlick = 0;
        return candidate;
    }

    private void push(float dYaw, float dPitch) {
        yawDeltas[head] = dYaw;
        pitchDeltas[head] = dPitch;
        head = (head + 1) % MAX_WINDOW;
        if (filled < MAX_WINDOW) {
            filled++;
        }
    }

    private void clearWindow() {
        filled = 0;
        head = 0;
    }

    static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }
}
