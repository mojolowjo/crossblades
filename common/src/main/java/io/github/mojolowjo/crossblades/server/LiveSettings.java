package io.github.mojolowjo.crossblades.server;

import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.config.ConfigManager;
import io.github.mojolowjo.crossblades.core.CombatSettings;
import io.github.mojolowjo.crossblades.network.SettingsPayload;
import io.github.mojolowjo.crossblades.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Changing the rules while the server runs, and keeping every player's game in step with them. */
public final class LiveSettings {
    private LiveSettings() {
    }

    /**
     * A server (or your own singleplayer world) is starting: use the config file's settings, not
     * rules left over from a server you played on earlier.
     */
    public static void onServerStarting() {
        Crossblades.applySettings(Crossblades.loadSettingsFile());
    }

    /** Sends the current rules to a player who just joined. */
    public static void onPlayerJoin(ServerPlayer player) {
        if (!Services.PLATFORM.isFakePlayer(player)) {
            Services.PLATFORM.sendToPlayer(player, payload());
        }
    }

    /**
     * Puts {@code newSettings} into use right away, saves them to the config file and sends them
     * to everyone. The server's own client section is kept as it is.
     */
    public static void change(MinecraftServer server, CombatSettings newSettings, boolean save) {
        newSettings.client = Crossblades.settings().client;
        newSettings.sanitize();
        Crossblades.applySettings(newSettings);
        if (save) {
            Crossblades.saveSettingsFile(newSettings);
        }
        SettingsPayload payload = payload();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!Services.PLATFORM.isFakePlayer(player)) {
                Services.PLATFORM.sendToPlayer(player, payload);
            }
        }
    }

    private static SettingsPayload payload() {
        return new SettingsPayload(ConfigManager.rulesToJson(Crossblades.settings()));
    }
}
