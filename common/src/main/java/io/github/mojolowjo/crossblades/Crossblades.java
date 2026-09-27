package io.github.mojolowjo.crossblades;

import io.github.mojolowjo.crossblades.config.ConfigManager;
import io.github.mojolowjo.crossblades.core.CombatSettings;
import io.github.mojolowjo.crossblades.platform.Services;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared constants and startup for both loaders. */
public final class Crossblades {
    public static final String MOD_ID = "crossblades";
    public static final String MOD_NAME = "Crossblades";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    /** Items that use directional combat. Edit with a datapack: {@code data/crossblades/tags/item/melee_weapons.json}. */
    public static final TagKey<Item> MELEE_WEAPONS = TagKey.create(Registries.ITEM, id("melee_weapons"));

    /** Damage type for directional swings. Tagged to bypass shields and hit cooldowns (see data folder). */
    public static final ResourceKey<DamageType> SWING_DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, id("swing"));

    private static CombatSettings settings = new CombatSettings();
    private static boolean initialized;

    private Crossblades() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static CombatSettings settings() {
        return settings;
    }

    public static boolean isWeapon(ItemStack stack) {
        return !stack.isEmpty() && stack.is(MELEE_WEAPONS);
    }

    /** Called by each loader's entry points; safe to call more than once. */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        settings = ConfigManager.load(Services.PLATFORM.getConfigDir());
        LOG.info("Crossblades loaded on {}", Services.PLATFORM.getPlatformName());
    }
}
