package io.github.mojolowjo.crossblades.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
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
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

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
        write(file, settings);
        return settings;
    }

    public static void save(Path configDir, CombatSettings settings) {
        write(configDir.resolve(FILE_NAME), settings);
    }

    /** The combat rules as JSON, without the personal client section (for sending to players). */
    public static String rulesToJson(CombatSettings settings) {
        JsonObject json = GSON.toJsonTree(settings).getAsJsonObject();
        json.remove("client");
        return COMPACT.toJson(json);
    }

    /** Reads rules sent by {@link #rulesToJson}; the client section gets defaults. */
    public static CombatSettings rulesFromJson(String json) {
        CombatSettings settings = COMPACT.fromJson(json, CombatSettings.class);
        if (settings == null) {
            throw new JsonParseException("empty settings");
        }
        return settings.sanitize();
    }

    private static void write(Path file, CombatSettings settings) {
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
