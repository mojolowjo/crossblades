package io.github.mojolowjo.crossblades.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds which hitboxes a swing touches. Plain math with no Minecraft classes, so it can be unit
 * tested on its own.
 * <p>
 * A swing is a fan of rays from the attacker's eyes: wide and flat for slashes, tall and narrow
 * for overheads, and a tight bundle for pokes. A target is hit if any ray enters its box within
 * the swing's reach.
 */
public final class HitScan {
    /** Rays are spaced at most this many degrees apart. */
    private static final double RAY_SPACING_DEGREES = 6.0;

    private HitScan() {
    }

    /** An axis-aligned box, like a Minecraft entity hitbox. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public Box inflate(double amount) {
            return new Box(minX - amount, minY - amount, minZ - amount, maxX + amount, maxY + amount, maxZ + amount);
        }
    }

    public record Target<T>(T id, Box box) {
    }

    /** A target the swing touched, and how far from the eyes the first ray hit it. */
    public record Hit<T>(T id, double distance, double x, double y, double z) {
    }

    /**
     * Minecraft's view direction for a yaw and pitch in degrees (same as {@code Vec3.directionFromRotation}).
     */
    public static double[] direction(double yawDegrees, double pitchDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double pitch = Math.toRadians(pitchDegrees);
        double cosPitch = Math.cos(pitch);
        return new double[] {-Math.sin(yaw) * cosPitch, -Math.sin(pitch), Math.cos(yaw) * cosPitch};
    }

    /**
     * Distance along the ray at which it enters the box, 0 if it starts inside, or -1 if it misses.
     * {@code d} does not need to be normalised; the result is in units of {@code d}'s length.
     */
    public static double rayBox(double ox, double oy, double oz, double dx, double dy, double dz, Box box) {
        double tMin = Double.NEGATIVE_INFINITY;
        double tMax = Double.POSITIVE_INFINITY;
        double[] origin = {ox, oy, oz};
        double[] dir = {dx, dy, dz};
        double[] min = {box.minX(), box.minY(), box.minZ()};
        double[] max = {box.maxX(), box.maxY(), box.maxZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(dir[axis]) < 1e-12) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return -1;
                }
            } else {
                double t1 = (min[axis] - origin[axis]) / dir[axis];
                double t2 = (max[axis] - origin[axis]) / dir[axis];
                if (t1 > t2) {
                    double swap = t1;
                    t1 = t2;
                    t2 = swap;
                }
                tMin = Math.max(tMin, t1);
                tMax = Math.min(tMax, t2);
                if (tMin > tMax) {
                    return -1;
                }
            }
        }
        if (tMax < 0) {
            return -1;
        }
        return Math.max(0, tMin);
    }

    /** Yaw/pitch offsets (degrees) of every ray in a swing's fan. */
    public static List<double[]> rayOffsets(CombatSettings.Attack attack) {
        List<double[]> offsets = new ArrayList<>();
        int yawSteps = steps(attack.horizontalArcDegrees);
        int pitchSteps = steps(attack.verticalArcDegrees);
        for (int i = -yawSteps; i <= yawSteps; i++) {
            double yawOffset = yawSteps == 0 ? 0 : attack.horizontalArcDegrees * i / yawSteps;
            for (int j = -pitchSteps; j <= pitchSteps; j++) {
                double pitchOffset = pitchSteps == 0 ? 0 : attack.verticalArcDegrees * j / pitchSteps;
                offsets.add(new double[] {yawOffset, pitchOffset});
            }
        }
        return offsets;
    }

    private static int steps(double halfArc) {
        return halfArc <= 0 ? 0 : (int) Math.ceil(halfArc / RAY_SPACING_DEGREES);
    }

    /**
     * Runs the swing and returns the targets it hits, nearest first, at most {@code attack.maxTargets}.
     *
     * @param padding extra size added around every box
     */
    public static <T> List<Hit<T>> scan(double eyeX, double eyeY, double eyeZ, float yaw, float pitch,
                                        CombatSettings.Attack attack, double padding, List<Target<T>> targets) {
        Map<T, Hit<T>> best = new LinkedHashMap<>();
        List<double[]> offsets = rayOffsets(attack);
        for (Target<T> target : targets) {
            Box box = target.box().inflate(padding);
            for (double[] offset : offsets) {
                double rayPitch = Math.max(-90, Math.min(90, pitch + offset[1]));
                double[] d = direction(yaw + offset[0], rayPitch);
                double t = rayBox(eyeX, eyeY, eyeZ, d[0], d[1], d[2], box);
                if (t >= 0 && t <= attack.reach) {
                    Hit<T> previous = best.get(target.id());
                    if (previous == null || t < previous.distance()) {
                        best.put(target.id(), new Hit<>(target.id(), t,
                                eyeX + d[0] * t, eyeY + d[1] * t, eyeZ + d[2] * t));
                    }
                }
            }
        }
        List<Hit<T>> hits = new ArrayList<>(best.values());
        hits.sort(Comparator.comparingDouble(Hit::distance));
        return hits.size() > attack.maxTargets ? new ArrayList<>(hits.subList(0, attack.maxTargets)) : hits;
    }

    /**
     * Whether a point lies within {@code coneDegrees} (to each side, measured flat on the ground) of
     * where someone at (fromX, fromZ) with the given yaw is looking.
     */
    public static boolean inFrontCone(double fromX, double fromZ, float yawDegrees, double pointX, double pointZ,
                                      double coneDegrees) {
        double dx = pointX - fromX;
        double dz = pointZ - fromZ;
        if (dx * dx + dz * dz < 1e-6) {
            return true;
        }
        double[] look = direction(yawDegrees, 0);
        double length = Math.sqrt(dx * dx + dz * dz);
        double cos = (look[0] * dx + look[2] * dz) / length;
        double angle = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cos))));
        return angle <= coneDegrees;
    }
}
