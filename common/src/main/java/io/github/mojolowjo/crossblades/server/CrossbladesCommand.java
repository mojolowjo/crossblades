package io.github.mojolowjo.crossblades.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.mojolowjo.crossblades.Crossblades;
import io.github.mojolowjo.crossblades.core.CombatSettings;
import io.github.mojolowjo.crossblades.core.SettingsEditor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

/**
 * {@code /crossblades}: look at and change the combat settings while playing.
 * <ul>
 *   <li>{@code /crossblades list [group]} - show settings (anyone)</li>
 *   <li>{@code /crossblades get <setting>} - one value and what it does (anyone)</li>
 *   <li>{@code /crossblades set <setting> <value>} - change it for everyone, saved right away (ops)</li>
 *   <li>{@code /crossblades reset <setting>|all} - back to the default (ops)</li>
 *   <li>{@code /crossblades reload} - read the config file again after editing it by hand (ops)</li>
 * </ul>
 */
public final class CrossbladesCommand {
    private static final List<String> GROUPS = List.of("flick", "overhead", "slash", "poke", "blocking", "parry", "other");
    private static final String ALL = "all";
    private static final String CHANGED = "changed";

    private static final SuggestionProvider<CommandSourceStack> SETTING_NAMES = (context, builder) ->
            SharedSuggestionProvider.suggest(SettingsEditor.keys(Crossblades.settings()), builder);

    private static final SuggestionProvider<CommandSourceStack> LIST_FILTERS = (context, builder) -> {
        List<String> filters = new ArrayList<>(GROUPS);
        filters.add(CHANGED);
        filters.add(ALL);
        return SharedSuggestionProvider.suggest(filters, builder);
    };

    /** Suggests the current and the default value for the chosen setting. */
    private static final SuggestionProvider<CommandSourceStack> VALUES = (context, builder) -> {
        String key = StringArgumentType.getString(context, "setting");
        CombatSettings current = Crossblades.settings();
        List<String> values = new ArrayList<>();
        if (SettingsEditor.exists(current, key)) {
            values.add(SettingsEditor.get(current, key));
            CombatSettings defaults = new CombatSettings();
            if (SettingsEditor.exists(defaults, key)) {
                String fallback = SettingsEditor.get(defaults, key);
                if (!values.contains(fallback)) {
                    values.add(fallback);
                }
            }
            if (values.contains("true") || values.contains("false")) {
                values = List.of("true", "false");
            }
        }
        return SharedSuggestionProvider.suggest(values, builder);
    };

    private CrossbladesCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("crossblades")
                .executes(context -> help(context.getSource()))
                .then(Commands.literal("list")
                        .executes(context -> listGroups(context.getSource()))
                        .then(Commands.argument("filter", StringArgumentType.word())
                                .suggests(LIST_FILTERS)
                                .executes(context -> list(context.getSource(), StringArgumentType.getString(context, "filter")))))
                .then(Commands.literal("get")
                        .then(Commands.argument("setting", StringArgumentType.word())
                                .suggests(SETTING_NAMES)
                                .executes(context -> get(context.getSource(), setting(context)))))
                .then(Commands.literal("set")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("setting", StringArgumentType.word())
                                .suggests(SETTING_NAMES)
                                // The rest of the line, so "20%" and "15°" work too.
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .suggests(VALUES)
                                        .executes(context -> set(context.getSource(), setting(context),
                                                StringArgumentType.getString(context, "value"))))))
                .then(Commands.literal("reset")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal(ALL)
                                .executes(context -> resetAll(context.getSource())))
                        .then(Commands.argument("setting", StringArgumentType.word())
                                .suggests(SETTING_NAMES)
                                .executes(context -> reset(context.getSource(), setting(context)))))
                .then(Commands.literal("reload")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> reload(context.getSource()))));
    }

    private static String setting(CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "setting");
    }

    // ---- read ----

    private static int help(CommandSourceStack source) {
        tell(source, title("Crossblades settings"));
        tell(source, usage("/crossblades list ", "[group]", "show settings (try flick, parry, changed)"));
        tell(source, usage("/crossblades get ", "<setting>", "one value and what it does"));
        tell(source, usage("/crossblades set ", "<setting> <value>", "change it for everyone, saved right away"));
        tell(source, usage("/crossblades reset ", "<setting>|all", "back to the default"));
        tell(source, usage("/crossblades reload", "", "read config/crossblades.json again"));
        return 1;
    }

    private static int listGroups(CommandSourceStack source) {
        tell(source, title("Crossblades settings: pick a group"));
        MutableComponent line = Component.literal(" ");
        List<String> filters = new ArrayList<>(GROUPS);
        filters.add(CHANGED);
        filters.add(ALL);
        for (String filter : filters) {
            String command = "/crossblades list " + filter;
            line.append(Component.literal("[" + filter + "]").withStyle(style -> style
                    .withColor(ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent.RunCommand(command))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(command)))));
            line.append(" ");
        }
        tell(source, line);
        return 1;
    }

    private static int list(CommandSourceStack source, String filter) {
        CombatSettings current = Crossblades.settings();
        CombatSettings defaults = new CombatSettings();
        String wanted = filter.toLowerCase(Locale.ROOT);
        List<String> shown = new ArrayList<>();
        for (String key : SettingsEditor.keys(current)) {
            boolean match;
            if (wanted.equals(ALL)) {
                match = true;
            } else if (wanted.equals(CHANGED)) {
                match = !SettingsEditor.exists(defaults, key)
                        || !SettingsEditor.get(defaults, key).equals(SettingsEditor.get(current, key));
            } else if (GROUPS.contains(wanted)) {
                match = group(key).equals(wanted);
            } else {
                match = key.toLowerCase(Locale.ROOT).contains(wanted);
            }
            if (match) {
                shown.add(key);
            }
        }
        if (shown.isEmpty()) {
            if (wanted.equals(CHANGED)) {
                tell(source, Component.literal("Everything is at its default.").withStyle(ChatFormatting.GRAY));
                return 1;
            }
            source.sendFailure(Component.literal("No settings match \"" + filter + "\". Try /crossblades list"));
            return 0;
        }
        tell(source, title("Crossblades: " + filter + " (click one to change it)"));
        for (String key : shown) {
            tell(source, settingLine(key, current, defaults));
        }
        return shown.size();
    }

    private static int get(CommandSourceStack source, String rawKey) {
        CombatSettings current = Crossblades.settings();
        if (!SettingsEditor.exists(current, rawKey)) {
            return unknown(source, rawKey);
        }
        String key = SettingsEditor.canonical(current, rawKey);
        tell(source, settingLine(key, current, new CombatSettings()));
        tell(source, Component.literal("  " + SettingsEditor.describe(key)).withStyle(ChatFormatting.GRAY));
        return 1;
    }

    /** "  flick.thresholdDegrees = 11°  (default 15°)", clickable to fill in a set command. */
    private static MutableComponent settingLine(String key, CombatSettings current, CombatSettings defaults) {
        String unit = SettingsEditor.unit(key);
        String value = SettingsEditor.get(current, key);
        String fallback = SettingsEditor.exists(defaults, key) ? SettingsEditor.get(defaults, key) : null;
        MutableComponent line = Component.literal("  " + key).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" = ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(value + unit).withStyle(ChatFormatting.WHITE));
        if (fallback != null && !fallback.equals(value)) {
            line.append(Component.literal("  (default " + fallback + unit + ")").withStyle(ChatFormatting.DARK_GRAY));
        }
        String suggestion = "/crossblades set " + key + " ";
        return line.withStyle(style -> style
                .withClickEvent(new ClickEvent.SuggestCommand(suggestion))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal(SettingsEditor.describe(key)))));
    }

    /** Which group of {@code /crossblades list <group>} a setting belongs to. */
    static String group(String key) {
        if (key.startsWith("flick.")) {
            return "flick";
        }
        if (key.startsWith("overhead.")) {
            return "overhead";
        }
        if (key.startsWith("slash.")) {
            return "slash";
        }
        if (key.startsWith("poke.")) {
            return "poke";
        }
        if (key.startsWith("block")) {
            return "blocking";
        }
        if (key.startsWith("parry") || key.startsWith("riposte")) {
            return "parry";
        }
        return "other";
    }

    // ---- change ----

    private static int set(CommandSourceStack source, String rawKey, String value) {
        CombatSettings current = Crossblades.settings();
        if (!SettingsEditor.exists(current, rawKey)) {
            return unknown(source, rawKey);
        }
        String key = SettingsEditor.canonical(current, rawKey);
        String before = SettingsEditor.get(current, key);
        CombatSettings changed = SettingsEditor.copy(current);
        try {
            SettingsEditor.set(changed, key, value);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
        String requested = SettingsEditor.get(changed, key);
        List<Integer> tierOrder = tierTimes(changed);
        changed.sanitize();
        boolean resorted = !tierOrder.equals(tierTimes(changed));
        String after = SettingsEditor.get(changed, key);
        if (!resorted && after.equals(before)) {
            tell(source, Component.literal(key + " is already " + before + SettingsEditor.unit(key)).withStyle(ChatFormatting.GRAY));
            return 1;
        }
        LiveSettings.change(source.getServer(), changed, true);

        String unit = SettingsEditor.unit(key);
        MutableComponent message = Component.literal(source.getTextName() + " set ")
                .append(Component.literal(key).withStyle(ChatFormatting.GOLD))
                .append(" to ")
                .append(Component.literal((resorted ? requested : after) + unit).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" (was " + before + unit + ")").withStyle(ChatFormatting.GRAY));
        if (resorted) {
            message.append(Component.literal(" - block tiers were re-sorted by time, see /crossblades list blocking")
                    .withStyle(ChatFormatting.YELLOW));
        } else if (!after.equals(requested)) {
            message.append(Component.literal(" - " + requested + unit + " is outside its limits")
                    .withStyle(ChatFormatting.YELLOW));
        }
        broadcast(source, message);
        return 1;
    }

    private static List<Integer> tierTimes(CombatSettings settings) {
        List<Integer> times = new ArrayList<>();
        for (CombatSettings.BlockTier tier : settings.blockTiers) {
            times.add(tier == null ? -1 : tier.underMs);
        }
        return times;
    }

    private static int reset(CommandSourceStack source, String rawKey) {
        CombatSettings current = Crossblades.settings();
        if (!SettingsEditor.exists(current, rawKey)) {
            return unknown(source, rawKey);
        }
        String key = SettingsEditor.canonical(current, rawKey);
        CombatSettings defaults = new CombatSettings();
        if (!SettingsEditor.exists(defaults, key)) {
            source.sendFailure(Component.literal(key + " has no default (it was added in the config file)"));
            return 0;
        }
        return set(source, key, SettingsEditor.get(defaults, key));
    }

    private static int resetAll(CommandSourceStack source) {
        LiveSettings.change(source.getServer(), new CombatSettings(), true);
        broadcast(source, Component.literal(source.getTextName() + " reset all combat settings to their defaults"));
        return 1;
    }

    private static int reload(CommandSourceStack source) {
        LiveSettings.change(source.getServer(), Crossblades.loadSettingsFile(), false);
        broadcast(source, Component.literal(source.getTextName() + " reloaded the combat settings from the config file"));
        return 1;
    }

    // ---- output ----

    /** Tells every player on the server, so the people you're testing with know what changed. */
    private static void broadcast(CommandSourceStack source, MutableComponent message) {
        MutableComponent full = Component.literal("[Crossblades] ").withStyle(ChatFormatting.DARK_AQUA).append(message);
        source.getServer().getPlayerList().broadcastSystemMessage(full, false);
        if (source.getPlayer() == null) {
            // Console or command block: also answer the sender directly.
            source.sendSuccess(() -> full, false);
        }
    }

    private static int unknown(CommandSourceStack source, String key) {
        source.sendFailure(Component.literal("There is no setting called \"" + key + "\". Try /crossblades list all"));
        return 0;
    }

    private static void tell(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, false);
    }

    private static MutableComponent title(String text) {
        return Component.literal(text).withStyle(ChatFormatting.DARK_AQUA, ChatFormatting.BOLD);
    }

    private static MutableComponent usage(String command, String args, String what) {
        return Component.literal(" " + command).withStyle(style -> style
                        .withColor(ChatFormatting.GOLD)
                        .withClickEvent(new ClickEvent.SuggestCommand(command)))
                .append(Component.literal(args).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("  " + what).withStyle(ChatFormatting.GRAY));
    }
}
