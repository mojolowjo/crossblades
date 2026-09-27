package io.github.mojolowjo.crossblades.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the Minecraft-free combat rules. Run with {@code tools/run-core-tests.sh}
 * (needs only a JDK). Plain Java on purpose, so it runs without Gradle or downloads.
 */
public final class CoreTests {
    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        directions();
        blockTiers();
        flicks();
        hitScan();
        frontCone();
        fighterFlow();
        rules();
        settingsSanitize();

        System.out.println(passed + " checks passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAIL: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void check(boolean condition, String what) {
        if (condition) {
            passed++;
        } else {
            failures.add(what);
        }
    }

    private static void directions() {
        check(AttackDir.RIGHT.isStoppedBy(Guard.LEFT), "attack from their right is blocked on your left");
        check(!AttackDir.RIGHT.isStoppedBy(Guard.RIGHT), "attack from their right is not blocked on your right");
        check(AttackDir.LEFT.isStoppedBy(Guard.RIGHT), "attack from their left is blocked on your right");
        check(AttackDir.OVERHEAD.isStoppedBy(Guard.UP), "overhead blocked by up");
        check(!AttackDir.OVERHEAD.isStoppedBy(Guard.LEFT), "overhead not blocked by left");
        check(AttackDir.POKE.isStoppedBy(Guard.LEFT) && AttackDir.POKE.isStoppedBy(Guard.RIGHT), "poke blocked by either side");
        check(!AttackDir.POKE.isStoppedBy(Guard.UP), "poke not blocked by up");
        check(!AttackDir.POKE.isStoppedBy(null), "nothing is blocked by no guard");

        check(AttackDir.RIGHT.isParriedBy(AttackDir.LEFT), "right slash parried by attacking left");
        check(!AttackDir.RIGHT.isParriedBy(AttackDir.RIGHT), "right slash not parried by attacking right");
        check(AttackDir.OVERHEAD.isParriedBy(AttackDir.OVERHEAD), "overhead parried by overhead");
        check(AttackDir.POKE.isParriedBy(AttackDir.LEFT) && AttackDir.POKE.isParriedBy(AttackDir.RIGHT), "poke parried by a slash");
        for (AttackDir incoming : AttackDir.values()) {
            check(!incoming.isParriedBy(AttackDir.POKE), "pokes never parry " + incoming);
        }

        check(AttackDir.fromFlick(Flick.DOWN) == AttackDir.POKE, "flick down loads poke");
        check(AttackDir.fromFlick(Flick.UP) == AttackDir.OVERHEAD, "flick up loads overhead");
        check(Guard.fromFlick(Flick.DOWN, Guard.LEFT) == Guard.LEFT, "flick down keeps guard");
        check(Guard.fromFlick(Flick.RIGHT, Guard.LEFT) == Guard.RIGHT, "flick right sets guard right");
        for (AttackDir dir : AttackDir.values()) {
            check(AttackDir.byId(dir.id()) == dir, "attack id round trip " + dir);
        }
        check(AttackDir.byId(99) == AttackDir.POKE && Guard.byId(-1) == Guard.RIGHT, "bad ids fall back safely");
    }

    private static void blockTiers() {
        CombatSettings s = new CombatSettings();
        check(s.blockDamageTaken(0) == 0.0, "0ms is perfect");
        check(s.blockDamageTaken(50) == 0.0, "50ms is perfect");
        check(s.blockDamageTaken(99) == 0.0, "99ms is perfect");
        check(s.blockDamageTaken(100) == 0.2, "100ms takes 20%");
        check(s.blockDamageTaken(199) == 0.2, "199ms takes 20%");
        check(s.blockDamageTaken(250) == 0.4, "250ms takes 40%");
        check(s.blockDamageTaken(350) == 0.6, "350ms takes 60%");
        check(s.blockDamageTaken(400) == 0.8, "400ms takes 80% (cap)");
        check(s.blockDamageTaken(60_000) == 0.8, "a minute early still takes 80%");
    }

    private static void flicks() {
        FlickDetector f = new FlickDetector();
        f.configure(new CombatSettings().client); // 15 degrees within 100ms (2 ticks)
        f.update(0, 0);
        boolean any = false;
        float yaw = 0;
        for (int i = 0; i < 20; i++) {
            yaw += 4.5F; // fast tracking of a strafing enemy up close, about 90 degrees per second
            any |= f.update(yaw, 0) != null;
        }
        check(!any, "tracking a strafing enemy is not a flick");
        for (int i = 0; i < 20; i++) {
            yaw += 1.5F;
            f.update(yaw, 0);
        }

        check(f.update(yaw += 10, 0) == null, "half a flick is not a flick yet");
        check(f.update(yaw += 10, 0) == Flick.RIGHT, "quick turn right is a right flick");
        check(f.update(yaw -= 9, 0) == null && f.update(yaw -= 9, 0) == null, "re-centering left right after is ignored");
        for (int i = 0; i < 10; i++) {
            f.update(yaw, 0);
        }
        check(f.update(yaw -= 20, 0) == Flick.LEFT, "a later left flick counts");

        f.reset();
        f.update(100, 10);
        check(f.update(100, -8) == Flick.UP, "looking up quickly is an up flick");
        check(f.update(100, 10) == null, "coming back down right after an up flick is ignored");

        f.reset();
        f.update(0, 0);
        check(f.update(20, 0) == Flick.RIGHT, "flick right");
        check(f.update(-30, 0) == Flick.LEFT, "a big opposite flick overrides the re-aim grace");

        f.reset();
        f.update(0, 0);
        check(f.update(15, 14) == null, "diagonal movement is not a flick");

        f.reset();
        f.update(170, 0);
        check(f.update(-170, 0) == Flick.RIGHT, "turning right across the +/-180 seam is still right");

        f.reset();
        f.update(0, 0);
        check(f.update(0, 25) == Flick.DOWN, "looking down quickly is a down flick");

        f.reset();
        f.update(0, 0);
        check(f.update(175, 0) == null, "a teleport-sized jump is ignored");
    }

    private static HitScan.Target<String> player(String id, double x, double z) {
        return new HitScan.Target<>(id, new HitScan.Box(x - 0.3, 0, z - 0.3, x + 0.3, 1.8, z + 0.3));
    }

    private static void hitScan() {
        CombatSettings s = new CombatSettings();
        double eyeY = 1.62;
        // Facing yaw 0 = +Z. Turning right increases yaw and points toward -X.
        double[] right45 = HitScan.direction(45, 0);
        check(right45[0] < 0 && right45[2] > 0, "yaw 45 points toward -X,+Z");

        List<HitScan.Target<String>> ahead = List.of(player("a", 0, 2));
        check(HitScan.scan(0, eyeY, 0, 0, 0, s.slash, 0.15, ahead).size() == 1, "slash hits target ahead");
        check(HitScan.scan(0, eyeY, 0, 0, 0, s.poke, 0.15, ahead).size() == 1, "poke hits target ahead");
        check(HitScan.scan(0, eyeY, 0, 0, 0, s.overhead, 0.15, ahead).size() == 1, "overhead hits target ahead");

        List<HitScan.Target<String>> side = List.of(player("b", -1.4, 1.4));
        check(HitScan.scan(0, eyeY, 0, 0, 0, s.slash, 0.15, side).size() == 1, "slash reaches 45 degrees to the right");
        check(HitScan.scan(0, eyeY, 0, 0, 0, s.poke, 0.15, side).isEmpty(), "poke misses 45 degrees to the side");
        check(HitScan.scan(0, eyeY, 0, 0, 0, s.overhead, 0.15, side).isEmpty(), "overhead misses 45 degrees to the side");

        List<HitScan.Target<String>> far = List.of(player("c", 0, 4.3));
        check(HitScan.scan(0, eyeY, 0, 0, 0, s.poke, 0.15, far).isEmpty(), "poke does not reach 4 blocks");
        List<HitScan.Target<String>> mid = List.of(player("d", 0, 3.5));
        check(HitScan.scan(0, eyeY, 0, 0, 0, s.poke, 0.15, mid).size() == 1, "poke reaches further than a slash");
        check(HitScan.scan(0, eyeY, 0, 0, 0, s.slash, 0.15, mid).isEmpty(), "slash is shorter than a poke");

        List<HitScan.Target<String>> behind = List.of(player("e", 0, -2));
        check(HitScan.scan(0, eyeY, 0, 0, 0, s.slash, 0.15, behind).isEmpty(), "nothing hits behind you");

        List<HitScan.Target<String>> crowd = List.of(
                player("n1", 0, 1.5), player("n2", -0.8, 1.9), player("n3", 0.8, 1.9), player("n4", 0, 2.6));
        List<HitScan.Hit<String>> hits = HitScan.scan(0, eyeY, 0, 0, 0, s.slash, 0.15, crowd);
        check(hits.size() == 3, "slash hits at most 3");
        check(hits.get(0).id().equals("n1"), "nearest is hit first");
        check(HitScan.scan(0, eyeY, 0, 0, 0, s.poke, 0.15, crowd).size() == 1, "poke hits only one");

        // Crouching target slightly below: looking a bit down still connects.
        List<HitScan.Target<String>> low = List.of(new HitScan.Target<>("low",
                new HitScan.Box(-0.3, -0.5, 1.7, 0.3, 1.0, 2.3)));
        check(HitScan.scan(0, eyeY, 0, 0, 10, s.overhead, 0.15, low).size() == 1, "overhead hits a lower target");

        check(HitScan.rayBox(0, 0, 0, 0, 0, 1, new HitScan.Box(-1, -1, -1, 1, 1, 1)) == 0, "ray starting inside a box hits at 0");
        check(Math.abs(HitScan.rayBox(0, 0, 0, 0, 0, 1, new HitScan.Box(-1, -1, 2, 1, 1, 3)) - 2) < 1e-9, "ray enters box at 2");
        check(HitScan.rayBox(0, 0, 0, 0, 0, 1, new HitScan.Box(2, 2, 2, 3, 3, 3)) < 0, "ray misses box");
    }

    private static void frontCone() {
        check(HitScan.inFrontCone(0, 0, 0, 0, 3, 80), "attacker in front is inside the cone");
        check(!HitScan.inFrontCone(0, 0, 0, 0, -3, 80), "attacker behind is outside the cone");
        check(!HitScan.inFrontCone(0, 0, 0, 3, 0, 80), "attacker straight to the side is outside an 80 degree cone");
        check(HitScan.inFrontCone(0, 0, 0, 3, 3, 80), "attacker 45 degrees off is inside");
        check(HitScan.inFrontCone(0, 0, 90, -3, 0, 10), "yaw 90 looks toward -X");
    }

    private static void fighterFlow() {
        CombatSettings s = new CombatSettings();
        FighterState f = new FighterState();
        long t = 1000;
        f.tick(t, true, s);
        check(f.phase() == FighterState.Phase.IDLE, "starts idle");

        check(f.requestAttack(AttackDir.OVERHEAD, t, s), "can attack when idle");
        check(f.phase() == FighterState.Phase.WINDUP && f.phaseEnd() == t + 12, "overhead winds up for 12 ticks (600ms)");
        check(f.shown() == FighterState.Shown.WINDUP && f.shownTicks() == 12, "wind-up is shown to others");
        f.clearShown();
        check(!f.requestAttack(AttackDir.POKE, t + 1, s), "cannot start a second attack mid wind-up");
        check(!f.strikeDue(t + 11) && f.strikeDue(t + 12), "strike lands exactly at the end of the wind-up");

        f.finishStrike(t + 12, s);
        check(f.phase() == FighterState.Phase.RECOVERY && f.phaseEnd() == t + 20, "overhead recovers for 8 ticks");
        check(!f.requestAttack(AttackDir.LEFT, t + 14, s), "clicking early in recovery is dropped");
        check(f.requestAttack(AttackDir.LEFT, t + 18, s), "clicking at the end of recovery is queued");
        f.tick(t + 19, true, s);
        check(f.phase() == FighterState.Phase.RECOVERY, "still recovering");
        f.tick(t + 20, true, s);
        check(f.phase() == FighterState.Phase.WINDUP && f.attackDir() == AttackDir.LEFT, "queued attack starts when recovery ends");
        check(f.phaseEnd() == t + 20 + 9, "slash winds up for 9 ticks (450ms)");

        // Guard held during the wind-up only becomes active once idle again.
        f.setGuard(true, Guard.UP, t + 21);
        check(!f.guardActive(), "no guarding mid wind-up");
        f.finishStrike(t + 29, s);
        f.tick(t + 30, true, s);
        check(!f.guardActive(), "no guarding during recovery");
        f.tick(t + 36, true, s);
        check(f.guardActive() && f.guardSince() == t + 36, "guard starts counting when it becomes active");
        f.setGuard(true, Guard.LEFT, t + 40);
        check(f.guardDir() == Guard.LEFT && f.guardSince() == t + 40, "changing guard side restarts the timing");
        f.setGuard(true, Guard.LEFT, t + 45);
        check(f.guardSince() == t + 40, "re-sending the same guard does not restart the timing");
        f.setGuard(false, Guard.LEFT, t + 46);
        check(!f.guardActive() && f.shown() == FighterState.Shown.IDLE, "releasing block stops guarding");

        // Switching to a non-weapon cancels a wind-up and stops guarding.
        f.requestAttack(AttackDir.POKE, t + 50, s);
        f.tick(t + 51, false, s);
        check(f.phase() == FighterState.Phase.IDLE, "switching item cancels the wind-up");
        check(!f.requestAttack(AttackDir.POKE, t + 52, s), "cannot attack without a weapon");
        f.setGuard(true, Guard.UP, t + 53);
        check(!f.guardActive(), "cannot guard without a weapon");
        f.tick(t + 54, true, s);
        check(f.guardActive(), "guard comes back when the weapon is back in hand");

        // Flinch, stagger, riposte.
        FighterState g = new FighterState();
        g.tick(0, true, s);
        g.requestAttack(AttackDir.OVERHEAD, 0, s);
        g.flinch(3, s);
        check(g.phase() == FighterState.Phase.RECOVERY && g.phaseEnd() == 3 + 6, "a flinch cancels the wind-up for 300ms");
        g.tick(9, true, s);
        g.requestAttack(AttackDir.RIGHT, 9, s);
        g.stagger(12, s);
        check(g.phase() == FighterState.Phase.STAGGER && g.phaseEnd() == 12 + 12, "a parried attacker is staggered 600ms");
        check(!g.requestAttack(AttackDir.RIGHT, 14, s), "no attacking while staggered");
        g.setGuard(true, Guard.UP, 14);
        check(!g.guardActive(), "no blocking while staggered");

        FighterState h = new FighterState();
        h.tick(0, true, s);
        h.requestAttack(AttackDir.OVERHEAD, 0, s);
        h.riposte(1, s);
        check(h.phaseEnd() == 1 + 3, "a riposte cuts the wind-up to 150ms");
    }

    private static void rules() {
        CombatSettings s = new CombatSettings();
        FighterState d = new FighterState();
        d.tick(100, true, s);
        d.setGuard(true, Guard.LEFT, 100);

        CombatRules.Outcome o = CombatRules.resolve(AttackDir.RIGHT, d, true, 101, s);
        check(o.kind() == CombatRules.Kind.PERFECT_BLOCK && o.damageTaken() == 0, "block 50ms before the hit is perfect");
        o = CombatRules.resolve(AttackDir.RIGHT, d, true, 103, s);
        check(o.kind() == CombatRules.Kind.BLOCK && o.damageTaken() == 0.2, "block 150ms before takes 20%");
        o = CombatRules.resolve(AttackDir.RIGHT, d, true, 200, s);
        check(o.kind() == CombatRules.Kind.BLOCK && o.damageTaken() == 0.8, "holding block for ages takes 80%");
        o = CombatRules.resolve(AttackDir.LEFT, d, true, 101, s);
        check(o.kind() == CombatRules.Kind.HIT && o.damageTaken() == 1, "wrong side is a clean hit");
        o = CombatRules.resolve(AttackDir.RIGHT, d, false, 101, s);
        check(o.kind() == CombatRules.Kind.HIT, "hit from behind cannot be blocked");
        o = CombatRules.resolve(AttackDir.POKE, d, true, 101, s);
        check(o.kind() == CombatRules.Kind.PERFECT_BLOCK, "side guard stops a poke");
        o = CombatRules.resolve(AttackDir.RIGHT, null, true, 101, s);
        check(o.kind() == CombatRules.Kind.HIT, "mobs never block");

        FighterState up = new FighterState();
        up.tick(0, true, s);
        up.setGuard(true, Guard.UP, 0);
        check(CombatRules.resolve(AttackDir.POKE, up, true, 1, s).kind() == CombatRules.Kind.HIT, "up guard does not stop a poke");
        check(CombatRules.resolve(AttackDir.OVERHEAD, up, true, 1, s).kind() == CombatRules.Kind.PERFECT_BLOCK, "up guard stops an overhead");

        FighterState p = new FighterState();
        p.tick(0, true, s);
        p.requestAttack(AttackDir.LEFT, 99, s);
        o = CombatRules.resolve(AttackDir.RIGHT, p, true, 100, s);
        check(o.kind() == CombatRules.Kind.PARRY && o.damageTaken() == 0, "attacking into the swing 50ms early parries");
        o = CombatRules.resolve(AttackDir.RIGHT, p, true, 101, s);
        check(o.kind() == CombatRules.Kind.HIT, "100ms early is outside the default parry window");
        o = CombatRules.resolve(AttackDir.LEFT, p, true, 100, s);
        check(o.kind() == CombatRules.Kind.HIT, "attacking the wrong side does not parry");
        check(CombatRules.resolve(AttackDir.POKE, p, true, 100, s).kind() == CombatRules.Kind.PARRY, "a slash parries a poke");
    }

    private static void settingsSanitize() {
        CombatSettings s = new CombatSettings();
        s.blockTiers = new ArrayList<>(List.of(new CombatSettings.BlockTier(300, 0.4), new CombatSettings.BlockTier(100, -1)));
        s.poke = null;
        s.client = null;
        s.sanitize();
        check(s.blockTiers.get(0).underMs == 100 && s.blockTiers.get(0).damageTaken == 0, "tiers are sorted and clamped");
        check(s.poke != null && s.poke.windupMs == 350, "a missing attack gets defaults");
        check(s.client != null, "a missing client section gets defaults");
        check(CombatSettings.ticks(100) == 2 && CombatSettings.ticks(10) == 1 && CombatSettings.ticks(475) == 10, "ms to ticks rounding");
    }
}
