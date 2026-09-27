package io.github.mojolowjo.crossblades.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.core.CombatSettings;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads {@code config/crossblades.json}, creating it with defaults on first run. Settings missing
 * from an older file keep their defaults, and the file is rewritten so new settings show up in it.
 */
public final class ConfigManager {
    public static final String FILE_NAME = "crossblades.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ConfigManager() {
    }

    public static CombatSettings load(Path configDir) {
        Path file = configDir.resolve(FILE_NAME);
        CombatSettings settings = null;
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                settings = GSON.fromJson(reader, CombatSettings.class);
            } catch (IOException | JsonParseException e) {
                Crossblades.LOG.error("Could not read {}; using default settings. Fix or delete the file to get rid of this error.", file, e);
                return new CombatSettings().sanitize();
            }
        }
        if (settings == null) {
            settings = new CombatSettings();
        }
        settings.sanitize();
        save(file, settings);
        return settings;
    }

    private static void save(Path file, CombatSettings settings) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(settings, writer);
            }
        } catch (IOException e) {
            Crossblades.LOG.warn("Could not write {}", file, e);
        }
    }
}
