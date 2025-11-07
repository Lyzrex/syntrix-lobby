package net.lyzrex.syntrix.lobby.utils;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Utility helpers for resolving {@link Sound} identifiers from configuration values.
 *
 * <p>The resolver intentionally avoids the deprecated {@code Sound#valueOf(String)} path and
 * instead attempts registry lookups first before consulting a cached alias table populated from
 * the active sound registry.</p>
 */
public final class SoundUtil {

    private static final Map<String, Sound> LEGACY_ENUM_LOOKUP = buildLegacyLookup();

    private SoundUtil() {}

    public static @NotNull Sound resolve(@Nullable String raw, @NotNull Sound fallback) {
        if (raw == null) {
            return fallback;
        }

        String in = raw.trim();
        if (in.isEmpty()) {
            return fallback;
        }

        for (String candidate : buildCandidates(in)) {
            Sound resolved = byRegistryKey(candidate);
            if (resolved != null) {
                return resolved;
            }
        }

        Sound legacy = tryLegacyEnumLookup(in);
        return legacy != null ? legacy : fallback;
    }

    private static @Nullable Sound byRegistryKey(String keyStr) {
        if (keyStr == null || keyStr.isBlank()) {
            return null;
        }

        String normalized = keyStr.toLowerCase(Locale.ROOT).replace(' ', '_');
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }

        NamespacedKey key = NamespacedKey.fromString(normalized);
        if (key == null) {
            return null;
        }

        try {
            return Registry.SOUNDS.get(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Set<String> buildCandidates(String input) {
        Set<String> set = new LinkedHashSet<>();
        String s = input.trim();

        set.add(s);

        String lower = s.toLowerCase(Locale.ROOT);
        set.add(lower);
        set.add(s.toUpperCase(Locale.ROOT));

        if (s.contains(".")) {
            set.add(s.replace('.', '_'));
            set.add(lower.replace('.', '_'));
        }

        if (s.contains("_")) {
            set.add(s.replace('_', '.'));
            set.add(lower.replace('_', '.'));
        }

        if (!s.contains(":")) {
            set.add("minecraft:" + s);
            set.add("minecraft:" + lower);
            set.add("minecraft:" + s.toUpperCase(Locale.ROOT));
        }

        String hyphenNormalized = lower.replace('-', '_');
        set.add(hyphenNormalized);
        set.add(hyphenNormalized.replace('_', '.'));
        set.add("minecraft:" + hyphenNormalized);
        set.add("minecraft:" + hyphenNormalized.replace('_', '.'));

        String upperLegacy = lower.replace('.', '_').toUpperCase(Locale.ROOT);
        set.add(upperLegacy);
        if (!s.contains(":")) {
            set.add("minecraft:" + upperLegacy);
        }

        return set;
    }

    private static Map<String, Sound> buildLegacyLookup() {
        Map<String, Sound> map = new HashMap<>();

        for (Sound sound : Sound.values()) {
            NamespacedKey key = null;
            try {
                key = Registry.SOUNDS.getKey(sound);
            } catch (Throwable ignored) {
                // The registry may be unavailable during bootstrap; fall back to enum data only.
            }

            // Always register the enum constant name variants as a last resort.
            registerAliasFamily(map, sound, sound.name());

            if (key == null) {
                continue;
            }

            String namespace = key.getNamespace();
            String value = key.getKey();

            registerAliasFamily(map, sound, value);
            registerAliasFamily(map, sound, namespace + ":" + value);

            String underscored = value.replace('.', '_');
            registerAliasFamily(map, sound, underscored);
            registerAliasFamily(map, sound, namespace + ":" + underscored);

            String dotted = value.replace('_', '.');
            registerAliasFamily(map, sound, dotted);
            registerAliasFamily(map, sound, namespace + ":" + dotted);

            String hyphenated = underscored.replace('_', '-');
            registerAliasFamily(map, sound, hyphenated);
            registerAliasFamily(map, sound, namespace + ":" + hyphenated);
        }

        return map;
    }

    private static void registerAliasFamily(Map<String, Sound> map, Sound sound, String alias) {
        if (alias == null) {
            return;
        }

        Set<String> pending = new LinkedHashSet<>();
        pending.add(alias);

        while (!pending.isEmpty()) {
            Iterator<String> it = pending.iterator();
            String candidate = it.next();
            it.remove();
            if (candidate == null) {
                continue;
            }

            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            boolean added = map.putIfAbsent(trimmed, sound) == null;
            if (!added) {
                continue;
            }

            String lower = trimmed.toLowerCase(Locale.ROOT);
            String upper = trimmed.toUpperCase(Locale.ROOT);

            if (!trimmed.equals(lower)) pending.add(lower);
            if (!trimmed.equals(upper)) pending.add(upper);

            if (trimmed.indexOf('.') >= 0) {
                pending.add(trimmed.replace('.', '_'));
                pending.add(trimmed.replace('.', '-'));
            }
            if (trimmed.indexOf('_') >= 0) {
                pending.add(trimmed.replace('_', '.'));
                pending.add(trimmed.replace('_', '-'));
            }
            if (trimmed.indexOf('-') >= 0) {
                pending.add(trimmed.replace('-', '_'));
                pending.add(trimmed.replace('-', '.'));
            }
        }
    }

    private static @Nullable Sound tryLegacyEnumLookup(String input) {
        for (String candidate : buildCandidates(input)) {
            Sound match = LEGACY_ENUM_LOOKUP.get(candidate);
            if (match != null) {
                return match;
            }
        }
        return null;
    }
}
