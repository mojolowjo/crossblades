package io.github.mojolowjo.crossblades.client;

import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.core.AttackDir;
import io.github.mojolowjo.crossblades.core.CombatRules;
import io.github.mojolowjo.crossblades.core.FighterState;
import io.github.mojolowjo.crossblades.core.Guard;
import io.github.mojolowjo.crossblades.network.AttackPayload;
import io.github.mojolowjo.crossblades.platform.Services;
import io.github.mojolowjo.crossblades.server.CombatServer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/**
 * An end-to-end test that plays the mod inside a real game. Only runs when the game is started
 * with {@code -Dcrossblades.selftest=true} or a file named {@code crossblades-selftest} exists in
 * the config folder (the CI does this); it does nothing otherwise.
 * <p>
 * It drives the real inputs (camera turns for flicks, the attack and use keys) in singleplayer,
 * against a husk and a dummy fake-player attacker, and checks the results on the server:
 * swing damage, blocking by direction and timing, and parrying. If anything fails the game exits
 * with an error so the CI run fails.
 */
public final class CombatSelfTest {
    public static final boolean ENABLED = Boolean.getBoolean("crossblades.selftest")
            || Files.exists(Services.PLATFORM.getConfigDir().resolve("crossblades-selftest"));
    private static final double ARENA_Y = 200;
    private static final float DUMMY_DAMAGE = 10;

    private static final List<String> failures = new ArrayList<>();
    private static int passed;
    private static int step;
    private static int waitTicks;
    private static boolean finished;

    private static double arenaX;
    private static double arenaZ;
    private static UUID huskId;
    private static ServerPlayer dummy;
    private static float healthBefore;
    private static long clashSerial;
    private static int savedParryWindow;

    private CombatSelfTest() {
    }

    /** Runs at the end of every client tick. */
    public static void onClientTickEnd(Minecraft mc) {
        if (!ENABLED || finished) {
            return;
        }
        LocalPlayer player = mc.player;
        IntegratedServer server = mc.getSingleplayerServer();
        if (player == null || mc.level == null || server == null || mc.screen != null) {
            return;
        }
        // Keep the test harness (mc-runtime-test) from quitting until we are done.
        if (player.tickCount > 60) {
            player.tickCount = 60;
        }
        if (step == 0 && player.tickCount < 40) {
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        try {
            runStep(mc, player, server);
        } catch (Throwable t) {
            Crossblades.LOG.error("[self-test] step {} crashed", step, t);
            failures.add("step " + step + " threw " + t);
            finish(mc, server);
        }
    }

    private static void runStep(Minecraft mc, LocalPlayer player, IntegratedServer server) {
        KeyMapping attackKey = mc.options.keyAttack;
        KeyMapping useKey = mc.options.keyUse;
        switch (step) {
            case 0 -> {
                log("starting");
                onServer(server, () -> {
                    ServerPlayer sp = serverPlayer(server, player);
                    arenaX = Math.floor(sp.getX()) + 0.5;
                    arenaZ = Math.floor(sp.getZ()) + 0.5;
                    int x = (int) Math.floor(arenaX);
                    int z = (int) Math.floor(arenaZ);
                    command(server, "difficulty normal");
                    command(server, "gamemode survival " + sp.getScoreboardName());
                    command(server, String.format(Locale.ROOT, "fill %d 199 %d %d 199 %d minecraft:stone", x - 6, z - 6, x + 6, z + 6));
                    command(server, String.format(Locale.ROOT, "fill %d 200 %d %d 204 %d minecraft:air", x - 6, z - 6, x + 6, z + 6));
                    command(server, String.format(Locale.ROOT, "tp %s %.2f %.1f %.2f 0 0", sp.getScoreboardName(), arenaX, ARENA_Y, arenaZ));
                    command(server, String.format(Locale.ROOT,
                            "summon minecraft:husk %.2f %.1f %.2f {NoAI:1b,PersistenceRequired:1b,Silent:1b}", arenaX, ARENA_Y, arenaZ + 2.5));
                    sp.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
                    sp.setHealth(20);
                    ServerLevel level = (ServerLevel) sp.level();
                    List<Mob> mobs = level.getEntitiesOfClass(Mob.class,
                            new AABB(arenaX - 1.5, ARENA_Y - 1, arenaZ + 1, arenaX + 1.5, ARENA_Y + 3, arenaZ + 4));
                    huskId = mobs.isEmpty() ? null : mobs.get(0).getUUID();
                    return null;
                });
                check(huskId != null, "a husk was summoned in front of the player");
                next(20);
            }
            case 1 -> {
                player.setYRot(0);
                player.setXRot(0);
                check(CombatAnimations.missingAnimations().isEmpty(), "all animations loaded (missing: " + CombatAnimations.missingAnimations() + ")");
                check(CombatClient.isActive(), "combat stance is active with a sword");
                next(3);
            }
            // Flick right, then re-aim back to the target.
            case 2 -> {
                player.setYRot(20);
                next(0);
            }
            case 3 -> {
                player.setYRot(0);
                next(2);
            }
            case 4 -> {
                check(CombatClient.loadedAttack() == AttackDir.RIGHT, "flick right then re-aim loads a right slash (got " + CombatClient.loadedAttack() + ")");
                healthBefore = onServer(server, () -> husk(server, player).getHealth());
                KeyMapping.click(Services.PLATFORM.boundKey(attackKey));
                next(1);
            }
            case 5 -> {
                check(CombatClient.localPhase() == FighterState.Phase.WINDUP, "clicking starts a wind-up right away");
                check(CombatAnimations.isPlaying(player), "the wind-up animation is playing");
                next(16);
            }
            case 6 -> {
                float after = onServer(server, () -> husk(server, player).getHealth());
                float dealt = healthBefore - after;
                check(dealt > 5 && dealt < 8.5, "a diamond-sword slash hits the husk once for about 7 (dealt " + dealt + ")");
                onServer(server, () -> {
                    husk(server, player).discard();
                    ServerPlayer sp = serverPlayer(server, player);
                    ServerLevel level = (ServerLevel) sp.level();
                    dummy = Services.PLATFORM.createFakePlayer(level, "CrossbladesDummy");
                    dummy.setPos(arenaX, ARENA_Y, arenaZ + 2.2);
                    dummy.setYRot(180);
                    dummy.setYHeadRot(180);
                    dummy.setXRot(0);
                    dummy.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
                    dummy.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(DUMMY_DAMAGE);
                    sp.setHealth(20);
                    return null;
                });
                next(2);
            }
            // Guard left (correct side against a right slash), held for a long time: 80% damage.
            case 7 -> {
                player.setYRot(-20);
                next(0);
            }
            case 8 -> {
                player.setYRot(0);
                next(2);
            }
            case 9 -> {
                check(CombatClient.guardDir() == Guard.LEFT, "flick left sets the guard left (got " + CombatClient.guardDir() + ")");
                KeyMapping.set(Services.PLATFORM.boundKey(useKey), true);
                next(14);
            }
            case 10 -> {
                check(CombatClient.isGuarding(), "holding use with a sword guards");
                dummyAttacks(server, AttackDir.RIGHT);
                next(16);
            }
            case 11 -> {
                CombatServer.LastClash clash = CombatServer.lastClash();
                float lost = 20 - onServer(server, () -> serverPlayer(server, player).getHealth());
                check(clash.serial() > clashSerial && clash.kind() == CombatRules.Kind.BLOCK && clash.damageTaken() == 0.8,
                        "an early guard on the right side blocks 20% (got " + clash + ")");
                check(Math.abs(lost - DUMMY_DAMAGE * 0.8F) < 0.6F, "and the player takes 80% of 10 (took " + lost + ")");
                resetArena(server, player);
                next(5);
            }
            // Wrong side: full damage.
            case 12 -> {
                player.setYRot(20);
                next(0);
            }
            case 13 -> {
                player.setYRot(0);
                next(2);
            }
            case 14 -> {
                check(CombatClient.guardDir() == Guard.RIGHT, "flick right moves the guard right");
                dummyAttacks(server, AttackDir.RIGHT);
                next(16);
            }
            case 15 -> {
                CombatServer.LastClash clash = CombatServer.lastClash();
                float lost = 20 - onServer(server, () -> serverPlayer(server, player).getHealth());
                check(clash.serial() > clashSerial && clash.kind() == CombatRules.Kind.HIT, "guarding the wrong side does not block (got " + clash + ")");
                check(Math.abs(lost - DUMMY_DAMAGE) < 0.6F, "and the player takes full damage (took " + lost + ")");
                KeyMapping.set(Services.PLATFORM.boundKey(useKey), false);
                resetArena(server, player);
                next(5);
            }
            // Late guard, raised just before the hit: perfect or nearly perfect.
            case 16 -> {
                player.setYRot(-20);
                next(0);
            }
            case 17 -> {
                player.setYRot(0);
                next(12);
            }
            case 18 -> {
                check(!CombatClient.isGuarding(), "releasing use stops guarding");
                dummyAttacks(server, AttackDir.RIGHT);
                next(6);
            }
            case 19 -> {
                KeyMapping.set(Services.PLATFORM.boundKey(useKey), true);
                next(10);
            }
            case 20 -> {
                CombatServer.LastClash clash = CombatServer.lastClash();
                float lost = 20 - onServer(server, () -> serverPlayer(server, player).getHealth());
                boolean blocked = clash.kind() == CombatRules.Kind.PERFECT_BLOCK || clash.kind() == CombatRules.Kind.BLOCK;
                check(clash.serial() > clashSerial && blocked && clash.damageTaken() <= 0.4,
                        "a guard raised just before the hit blocks most or all damage (got " + clash + ")");
                check(Math.abs(lost - DUMMY_DAMAGE * clash.damageTaken()) < 0.6F, "and damage matches the tier (took " + lost + ")");
                log("late guard result: " + clash.kind() + ", took " + Math.round(clash.damageTaken() * 100) + "%");
                KeyMapping.set(Services.PLATFORM.boundKey(useKey), false);
                resetArena(server, player);
                // Parry timing within one tick is too tight to hit reliably from a test, so widen it.
                savedParryWindow = Crossblades.settings().parryWindowMs;
                Crossblades.settings().parryWindowMs = 400;
                next(12);
            }
            // Parry: attack toward the side the swing comes from (left) just before it lands.
            case 21 -> {
                check(CombatClient.loadedAttack() == AttackDir.LEFT, "the last flick (left) also loaded a left slash");
                dummyAttacks(server, AttackDir.RIGHT);
                next(5);
            }
            case 22 -> {
                KeyMapping.click(Services.PLATFORM.boundKey(attackKey));
                next(8);
            }
            case 23 -> {
                CombatServer.LastClash clash = CombatServer.lastClash();
                float lost = 20 - onServer(server, () -> serverPlayer(server, player).getHealth());
                FighterState dummyState = CombatServer.stateOf(dummy.getUUID());
                check(clash.serial() > clashSerial && clash.kind() == CombatRules.Kind.PARRY, "attacking into the swing parries it (got " + clash + ")");
                check(lost < 0.01F, "a parry takes no damage (took " + lost + ")");
                check(dummyState != null && dummyState.phase() == FighterState.Phase.STAGGER, "the parried attacker is staggered");
                Crossblades.settings().parryWindowMs = savedParryWindow;
                next(20);
            }
            default -> finish(mc, server);
        }
    }

    private static void dummyAttacks(IntegratedServer server, AttackDir dir) {
        clashSerial = CombatServer.lastClash().serial();
        onServer(server, () -> {
            CombatServer.onAttack(new AttackPayload(dir.id()), dummy);
            return null;
        });
    }

    /** Heals the player and puts them back on their spot facing the dummy (hits knock them back). */
    private static void resetArena(IntegratedServer server, LocalPlayer player) {
        onServer(server, () -> {
            ServerPlayer sp = serverPlayer(server, player);
            sp.setHealth(20);
            command(server, String.format(Locale.ROOT, "tp %s %.2f %.1f %.2f 0 0", sp.getScoreboardName(), arenaX, ARENA_Y, arenaZ));
            return null;
        });
    }

    private static void finish(Minecraft mc, IntegratedServer server) {
        finished = true;
        KeyMapping.set(Services.PLATFORM.boundKey(mc.options.keyUse), false);
        if (dummy != null) {
            ServerPlayer toRemove = dummy;
            onServer(server, () -> {
                CombatServer.onPlayerLeave(toRemove);
                return null;
            });
        }
        if (failures.isEmpty()) {
            log("PASSED (" + passed + " checks)");
        } else {
            log("FAILED: " + failures.size() + " of " + (passed + failures.size()) + " checks");
            for (String failure : failures) {
                log("  FAIL: " + failure);
            }
            System.exit(1);
        }
    }

    private static void next(int ticks) {
        step++;
        waitTicks = ticks;
    }

    private static void check(boolean ok, String what) {
        if (ok) {
            passed++;
            log("ok: " + what);
        } else {
            failures.add(what);
            log("FAIL: " + what);
        }
    }

    private static void log(String message) {
        Crossblades.LOG.info("[self-test] {}", message);
    }

    private static <T> T onServer(IntegratedServer server, Supplier<T> task) {
        return server.submit(task).join();
    }

    private static ServerPlayer serverPlayer(IntegratedServer server, LocalPlayer player) {
        return server.getPlayerList().getPlayer(player.getUUID());
    }

    private static LivingEntity husk(IntegratedServer server, LocalPlayer player) {
        Entity entity = ((ServerLevel) serverPlayer(server, player).level()).getEntity(huskId);
        if (entity instanceof LivingEntity living) {
            return living;
        }
        throw new IllegalStateException("husk is gone");
    }

    private static void command(IntegratedServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }
}
