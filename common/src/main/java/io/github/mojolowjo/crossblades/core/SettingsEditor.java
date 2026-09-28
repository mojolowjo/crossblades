package io.github.mojolowjo.crossblades.core;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and changes settings by name, for the {@code /crossblades} command:
 * {@code parryWindowMs}, {@code overhead.windupMs}, {@code flick.thresholdDegrees},
 * {@code blockTiers.0.damageTaken}, and so on.
 * <p>
 * Only the combat rules can be reached. The {@code client} section is each player's own and is
 * left out. Names are matched without caring about upper or lower case.
 */
public final class SettingsEditor {
    private static final String CLIENT_SECTION = "client";

    private SettingsEditor() {
    }

    /** Every setting name, in the order they appear in the config file. */
    public static List<String> keys(CombatSettings settings) {
        List<String> keys = new ArrayList<>();
        collect(settings, "", keys);
        return keys;
    }

    private static void collect(Object object, String prefix, List<String> out) {
        if (object instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collect(list.get(i), prefix + i + ".", out);
            }
            return;
        }
        for (Field field : fields(object.getClass())) {
            if (prefix.isEmpty() && field.getName().equals(CLIENT_SECTION)) {
                continue;
            }
            Object value = read(field, object);
            if (isLeaf(field.getType())) {
                out.add(prefix + field.getName());
            } else if (value != null) {
                collect(value, prefix + field.getName() + ".", out);
            }
        }
    }

    /** The current value as text, e.g. {@code "450"}, {@code "0.2"} or {@code "true"}. */
    public static String get(CombatSettings settings, String key) {
        Slot slot = find(settings, key);
        return format(read(slot.field, slot.owner));
    }

    /** The official spelling of {@code key} (fixes upper/lower case). */
    public static String canonical(CombatSettings settings, String key) {
        return find(settings, key).path;
    }

    public static boolean exists(CombatSettings settings, String key) {
        try {
            find(settings, key);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Changes one setting on {@code settings}. Numbers may end in {@code ms}, {@code °} or
     * {@code %} ({@code 20%} means 0.2). Call {@link CombatSettings#sanitize()} afterwards.
     *
     * @throws IllegalArgumentException with a message fit to show the player
     */
    public static void set(CombatSettings settings, String key, String text) {
        Slot slot = find(settings, key);
        Class<?> type = slot.field.getType();
        String value = text.trim().toLowerCase(Locale.ROOT);
        Object parsed;
        if (type == boolean.class) {
            parsed = switch (value) {
                case "true", "on", "yes", "1" -> true;
                case "false", "off", "no", "0" -> false;
                default -> throw new IllegalArgumentException(slot.path + " is on/off: use true or false");
            };
        } else {
            double number = parseNumber(value, slot.path);
            if (type == int.class) {
                if (number != Math.rint(number) || Math.abs(number) > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException(slot.path + " needs a whole number");
                }
                parsed = (int) number;
            } else {
                parsed = number;
            }
        }
        write(slot.field, slot.owner, parsed);
    }

    private static double parseNumber(String value, String path) {
        double scale = 1;
        String digits = value;
        if (digits.endsWith("%")) {
            scale = 0.01;
            digits = digits.substring(0, digits.length() - 1);
        } else if (digits.endsWith("ms")) {
            digits = digits.substring(0, digits.length() - 2);
        } else if (digits.endsWith("°")) {
            digits = digits.substring(0, digits.length() - 1);
        }
        try {
            double number = Double.parseDouble(digits.trim()) * scale;
            if (!Double.isFinite(number)) {
                throw new NumberFormatException();
            }
            return number;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(path + " needs a number, not \"" + value + "\"");
        }
    }

    /** A full copy, so a change can be made on the side and swapped in all at once. */
    public static CombatSettings copy(CombatSettings settings) {
        return (CombatSettings) deepCopy(settings);
    }

    private static Object deepCopy(Object object) {
        if (object == null || object instanceof Number || object instanceof Boolean || object instanceof String) {
            return object;
        }
        if (object instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object element : list) {
                copy.add(deepCopy(element));
            }
            return copy;
        }
        try {
            Object copy = object.getClass().getDeclaredConstructor().newInstance();
            for (Field field : fields(object.getClass())) {
                write(field, copy, deepCopy(read(field, object)));
            }
            return copy;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot copy " + object.getClass(), e);
        }
    }

    /** What the setting does, in one sentence. */
    public static String describe(String key) {
        String[] parts = key.split("\\.");
        String leaf = parts[parts.length - 1];
        String text = DESCRIPTIONS.getOrDefault(leaf, "");
        if (parts.length == 1) {
            return text;
        }
        String section = switch (parts[0]) {
            case "overhead" -> "Overhead: ";
            case "slash" -> "Left and right slashes: ";
            case "poke" -> "Poke: ";
            case "blockTiers" -> "Block tier " + parts[1] + ": ";
            case "flick" -> "Flicks: ";
            default -> "";
        };
        return section + text;
    }

    /** The unit shown after a value: {@code ms}, {@code °} or nothing. */
    public static String unit(String key) {
        if (key.endsWith("Ms")) {
            return "ms";
        }
        if (key.endsWith("Degrees")) {
            return "°";
        }
        return "";
    }

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("windupMs", "time from click to the hit. This is the opponent's time to react."),
            Map.entry("recoveryMs", "time after the hit before you can attack or block again."),
            Map.entry("damageMultiplier", "multiplies your normal attack damage (1 = normal, 1.3 = 30% more)."),
            Map.entry("reach", "how far the swing reaches, in blocks, from your eyes."),
            Map.entry("maxTargets", "how many enemies one swing can hit."),
            Map.entry("horizontalArcDegrees", "how wide the swing is, to each side of where you look."),
            Map.entry("verticalArcDegrees", "how tall the swing is, above and below where you look."),
            Map.entry("underMs", "applies when block was pressed less than this long before the hit."),
            Map.entry("damageTaken", "share of the damage you still take (0 = none, 0.2 = 20%)."),
            Map.entry("blockDamageAfterLastTier", "damage let through when block was pressed earlier than every tier (the cap)."),
            Map.entry("parryWindowMs", "attack in the matching direction this close before the enemy's hit to parry it."),
            Map.entry("parryStaggerMs", "how long a parried attacker is stunned."),
            Map.entry("riposteWindupMs", "after a parry, your attack's remaining wind-up is cut to this."),
            Map.entry("hitsInterruptWindups", "taking a clean hit while winding up cancels your attack."),
            Map.entry("hitFlinchMs", "how long you can't act after your wind-up is interrupted."),
            Map.entry("blockConeDegrees", "you can only block attacks coming from within this angle of where you look."),
            Map.entry("disableVanillaWeaponAttacks", "stop normal click attacks with weapons, so all weapon damage uses directional combat."),
            Map.entry("inputBufferMs", "an attack clicked this close to the end of your recovery is queued instead of dropped."),
            Map.entry("hitboxPadding", "extra size around every hitbox, in blocks, to make hits a little forgiving."),
            Map.entry("thresholdDegrees", "how far the camera must turn quickly to count as a flick. Lower = easier to flick."),
            Map.entry("windowMs", "the turn has to happen within this time. Higher = slower movements count too."),
            Map.entry("dominance", "the main direction must be this many times bigger than the other one (stops diagonal mix-ups)."),
            Map.entry("returnGraceMs", "after a flick, moving back the other way within this time counts as re-aiming, not a new flick."),
            Map.entry("returnOverride", "...unless that movement back is this many times the threshold."));

    // ---- reflection helpers ----

    private record Slot(Object owner, Field field, String path) {
    }

    private static Slot find(CombatSettings settings, String key) {
        String[] parts = key.trim().split("\\.");
        Object current = settings;
        StringBuilder path = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (path.length() > 0) {
                path.append('.');
            }
            boolean last = i == parts.length - 1;
            if (current instanceof List<?> list) {
                int index;
                try {
                    index = Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    throw unknown(key);
                }
                if (index < 0 || index >= list.size() || last) {
                    throw unknown(key);
                }
                path.append(index);
                current = list.get(index);
                continue;
            }
            Field field = field(current.getClass(), part);
            if (field == null || (i == 0 && field.getName().equals(CLIENT_SECTION))) {
                throw unknown(key);
            }
            path.append(field.getName());
            if (last) {
                if (!isLeaf(field.getType())) {
                    throw unknown(key);
                }
                return new Slot(current, field, path.toString());
            }
            if (isLeaf(field.getType())) {
                throw unknown(key);
            }
            current = read(field, current);
            if (current == null) {
                throw unknown(key);
            }
        }
        throw unknown(key);
    }

    private static IllegalArgumentException unknown(String key) {
        return new IllegalArgumentException("There is no setting called \"" + key + "\"");
    }

    private static Field field(Class<?> type, String name) {
        for (Field field : fields(type)) {
            if (field.getName().equalsIgnoreCase(name)) {
                return field;
            }
        }
        return null;
    }

    private static List<Field> fields(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field field : type.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (Modifier.isPublic(mods) && !Modifier.isStatic(mods) && !Modifier.isFinal(mods)) {
                out.add(field);
            }
        }
        return out;
    }

    private static boolean isLeaf(Class<?> type) {
        return type == int.class || type == double.class || type == boolean.class;
    }

    private static Object read(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(Field field, Object owner, Object value) {
        try {
            field.set(owner, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    static String format(Object value) {
        if (value instanceof Double d) {
            if (d == Math.rint(d) && Math.abs(d) < 1e9) {
                return Long.toString(d.longValue());
            }
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        return String.valueOf(value);
    }
}
