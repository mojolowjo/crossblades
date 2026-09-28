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

    /**
     * The settings in use. Never changed in place once published: a change builds a new copy and
     * swaps it in (see {@link #applySettings}), so the server and client threads always see a
     * complete set.
     */
    private static volatile CombatSettings settings = new CombatSettings();
    private static volatile int settingsVersion;
    private static boolean initialized;

    private Crossblades() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static CombatSettings settings() {
        return settings;
    }

    /** Goes up by one every time the settings are replaced, so code can notice a change. */
    public static int settingsVersion() {
        return settingsVersion;
    }

    /** Puts new settings into use right away (does not save them). */
    public static synchronized void applySettings(CombatSettings newSettings) {
        settings = newSettings;
        settingsVersion++;
    }

    /** Reads {@code config/crossblades.json} again. */
    public static CombatSettings loadSettingsFile() {
        return ConfigManager.load(Services.PLATFORM.getConfigDir());
    }

    /** Writes {@code config/crossblades.json}. */
    public static void saveSettingsFile(CombatSettings toSave) {
        ConfigManager.save(Services.PLATFORM.getConfigDir(), toSave);
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
        applySettings(loadSettingsFile());
        LOG.info("Crossblades loaded on {}", Services.PLATFORM.getPlatformName());
    }
}
