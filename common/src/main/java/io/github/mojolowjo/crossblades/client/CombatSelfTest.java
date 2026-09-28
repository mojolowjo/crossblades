package io.github.mojolowjo.crossblades.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.core.AttackDir;
import io.github.mojolowjo.crossblades.core.CombatRules;
import io.github.mojolowjo.crossblades.core.FighterState;
import io.github.mojolowjo.crossblades.core.Guard;
import io.github.mojolowjo.crossblades.network.AttackPayload;
import io.github.mojolowjo.crossblades.platform.Services;
import io.github.mojolowjo.crossblades.server.CombatServer;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
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

    private CombatSelfTest() {
    }

    // Frame sampling: records the arm's animated pitch every rendered frame to check the animation
    // moves smoothly between game ticks rather than stepping once per tick.
    private static boolean sampling;
    private static long sampleTick;
    private static final List<float[]> samples = new ArrayList<>();

    /** Runs every rendered frame (from the HUD). */
    public static void onFrame(Minecraft mc, float partialTick) {
        if (sampling && mc.player != null) {
            samples.add(new float[] {sampleTick, partialTick, CombatAnimations.probeRightArmPitch(mc.player)});
        }
    }

    private static void reportSamples(String label) {
        java.util.Map<Integer, List<Float>> byTick = new java.util.TreeMap<>();
        for (float[] sample : samples) {
            byTick.computeIfAbsent((int) sample[0], t -> new ArrayList<>()).add(sample[2]);
        }
        int multiFrameTicks = 0;
        int movingWithinTick = 0;
        for (List<Float> values : byTick.values()) {
            if (values.size() > 1) {
                multiFrameTicks++;
                float min = Float.MAX_VALUE;
                float max = -Float.MAX_VALUE;
                for (float v : values) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
                if (max - min > 1e-4F) {
                    movingWithinTick++;
                }
            }
        }
        log(String.format(Locale.ROOT, "frame sampling (%s): %d frames over %d ticks (~%.0f fps); %d ticks had several frames, "
                        + "the arm moved between frames in %d of them",
                label, samples.size(), byTick.size(), samples.size() / Math.max(1, byTick.size() * 0.05), multiFrameTicks, movingWithinTick));
    }

    /** Runs at the end of every client tick. */
    public static void onClientTickEnd(Minecraft mc) {
        sampleTick++;
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
                    stopRegeneration(sp);
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
                // Parry timing within one tick is too tight to hit reliably from a test, so widen it
                // with the in-game command (which also tests the command).
                check(commandResult(server, "crossblades set parryWindowMs fast") == 0
                        && Crossblades.settings().parryWindowMs == 100, "/crossblades set refuses a value that isn't a number");
                check(commandResult(server, "crossblades set parryWindowMs 400") == 1
                        && Crossblades.settings().parryWindowMs == 400, "/crossblades set changes a setting right away");
                check(configFileContains("\"parryWindowMs\": 400"), "and saves it to the config file");
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
                check(commandResult(server, "crossblades reset parryWindowMs") == 1
                        && Crossblades.settings().parryWindowMs == 100, "/crossblades reset puts a setting back to its default");
                next(20);
            }
            // Flick sensitivity can be tuned live: with a 30 degree threshold a 20 degree flick
            // no longer counts, and after a reset it does again.
            case 24 -> {
                check(commandResult(server, "crossblades set flick.thresholdDegrees 30") == 1
                        && Crossblades.settings().flick.thresholdDegrees == 30, "/crossblades set changes the flick threshold");
                check(commandResult(server, "crossblades") == 1, "/crossblades shows help");
                check(commandResult(server, "crossblades list") == 1, "/crossblades list shows the groups");
                check(commandResult(server, "crossblades list flick") == 5, "/crossblades list flick shows the 5 flick settings");
                check(commandResult(server, "crossblades list changed") == 1, "/crossblades list changed shows the one changed setting");
                check(commandResult(server, "crossblades get FLICK.thresholddegrees") == 1, "/crossblades get works and ignores case");
                check(commandResult(server, "crossblades get nothing.here") == 0, "/crossblades get refuses an unknown setting");
                next(2);
            }
            case 25 -> {
                player.setYRot(20);
                next(0);
            }
            case 26 -> {
                player.setYRot(0);
                next(2);
            }
            case 27 -> {
                check(CombatClient.loadedAttack() == AttackDir.LEFT,
                        "with a 30 degree threshold a 20 degree flick doesn't count (got " + CombatClient.loadedAttack() + ")");
                check(commandResult(server, "crossblades reset flick.thresholdDegrees") == 1
                        && Crossblades.settings().flick.thresholdDegrees == 15, "/crossblades reset puts the flick threshold back");
                next(2);
            }
            case 28 -> {
                player.setYRot(20);
                next(0);
            }
            case 29 -> {
                player.setYRot(0);
                next(2);
            }
            case 30 -> {
                check(CombatClient.loadedAttack() == AttackDir.RIGHT,
                        "after the reset the same flick counts again (got " + CombatClient.loadedAttack() + ")");
                next(5);
            }
            case 31 -> {
                // Photo session: screenshots of the poses so they can be checked by eye.
                resetArena(server, player);
                photoScript = buildPhotoScript(mc, player, attackKey, useKey);
                photoIndex = 0;
                next(10);
            }
            case 32 -> {
                if (photoIndex < photoScript.size()) {
                    PhotoStep photo = photoScript.get(photoIndex++);
                    photo.action().run();
                    waitTicks = photo.waitAfter();
                } else {
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    next(5);
                }
            }
            default -> finish(mc, server);
        }
    }

    private record PhotoStep(Runnable action, int waitAfter) {
    }

    private static List<PhotoStep> photoScript = List.of();
    private static int photoIndex;

    private static List<PhotoStep> buildPhotoScript(Minecraft mc, LocalPlayer player, KeyMapping attackKey, KeyMapping useKey) {
        List<PhotoStep> script = new ArrayList<>();
        script.add(new PhotoStep(() -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT), 5));
        script.add(new PhotoStep(() -> screenshot(mc, "third_person_idle"), 3));
        addAttackPhotos(script, mc, player, attackKey, "overhead", 0, -20, 8);
        addAttackPhotos(script, mc, player, attackKey, "right", 20, 0, 6);
        addAttackPhotos(script, mc, player, attackKey, "left", -20, 0, 6);
        addAttackPhotos(script, mc, player, attackKey, "poke", 0, 20, 5);
        addGuardPhoto(script, mc, player, useKey, "up", 0, -20);
        addGuardPhoto(script, mc, player, useKey, "left", -20, 0);
        addGuardPhoto(script, mc, player, useKey, "right", 20, 0);
        addFrameSampling(script, player, attackKey, true);
        addFrameSampling(script, player, attackKey, false);
        // First person: the HUD arrow during a wind-up, and the guard bar.
        script.add(new PhotoStep(() -> mc.options.setCameraType(CameraType.FIRST_PERSON), 5));
        addAttackPhotos(script, mc, player, attackKey, "fp_right", 20, 0, 6);
        addGuardPhoto(script, mc, player, useKey, "fp_left", -20, 0);
        return script;
    }

    private static void addAttackPhotos(List<PhotoStep> script, Minecraft mc, LocalPlayer player, KeyMapping attackKey,
                                        String name, float flickYaw, float flickPitch, int ticksIntoWindup) {
        script.add(new PhotoStep(() -> {
            player.setYRot(flickYaw);
            player.setXRot(flickPitch);
        }, 0));
        script.add(new PhotoStep(() -> {
            player.setYRot(0);
            player.setXRot(0);
        }, 3));
        script.add(new PhotoStep(() -> KeyMapping.click(Services.PLATFORM.boundKey(attackKey)), ticksIntoWindup));
        script.add(new PhotoStep(() -> screenshot(mc, "windup_" + name), 4));
        script.add(new PhotoStep(() -> screenshot(mc, "strike_" + name), 30));
    }

    private static void addFrameSampling(List<PhotoStep> script, LocalPlayer player, KeyMapping attackKey, boolean speedModifier) {
        script.add(new PhotoStep(() -> {
            CombatAnimations.forceSpeedModifier = speedModifier;
            player.setXRot(-20);
        }, 0));
        script.add(new PhotoStep(() -> player.setXRot(0), 3));
        script.add(new PhotoStep(() -> {
            samples.clear();
            sampling = true;
            KeyMapping.click(Services.PLATFORM.boundKey(attackKey));
        }, 12));
        script.add(new PhotoStep(() -> {
            sampling = false;
            reportSamples(speedModifier ? "with speed modifier" : "without speed modifier");
            CombatAnimations.forceSpeedModifier = false;
        }, 20));
    }

    private static void addGuardPhoto(List<PhotoStep> script, Minecraft mc, LocalPlayer player, KeyMapping useKey,
                                      String name, float flickYaw, float flickPitch) {
        script.add(new PhotoStep(() -> {
            player.setYRot(flickYaw);
            player.setXRot(flickPitch);
        }, 0));
        script.add(new PhotoStep(() -> {
            player.setYRot(0);
            player.setXRot(0);
        }, 3));
        script.add(new PhotoStep(() -> KeyMapping.set(Services.PLATFORM.boundKey(useKey), true), 6));
        script.add(new PhotoStep(() -> screenshot(mc, "guard_" + name), 2));
        script.add(new PhotoStep(() -> KeyMapping.set(Services.PLATFORM.boundKey(useKey), false), 12));
    }

    private static void screenshot(Minecraft mc, String name) {
        File dir = new File(mc.gameDirectory, "screenshots");
        dir.mkdirs();
        File file = new File(dir, "crossblades-" + name + ".png");
        Screenshot.takeScreenshot(mc.getMainRenderTarget(), image -> {
            try (image) {
                image.writeToFile(file);
            } catch (IOException e) {
                log("could not save screenshot " + name + ": " + e);
            }
        });
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
            stopRegeneration(sp);
            command(server, String.format(Locale.ROOT, "tp %s %.2f %.1f %.2f 0 0", sp.getScoreboardName(), arenaX, ARENA_Y, arenaZ));
            return null;
        });
    }

    /** Natural regeneration would heal the player between a hit and the health check. */
    private static void stopRegeneration(ServerPlayer sp) {
        sp.getFoodData().setFoodLevel(16);
        sp.getFoodData().setSaturation(0);
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

    /** Runs a command on the server and returns its result (-1 if it couldn't be parsed or crashed). */
    private static int commandResult(IntegratedServer server, String command) {
        return onServer(server, () -> {
            try {
                return server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack());
            } catch (CommandSyntaxException e) {
                log("command \"" + command + "\" failed: " + e.getMessage());
                return -1;
            } catch (RuntimeException e) {
                Crossblades.LOG.error("[self-test] command \"{}\" crashed", command, e);
                return -1;
            }
        });
    }

    private static boolean configFileContains(String text) {
        try {
            return Files.readString(Services.PLATFORM.getConfigDir().resolve("crossblades.json")).contains(text);
        } catch (IOException e) {
            log("could not read the config file: " + e);
            return false;
        }
    }
}
