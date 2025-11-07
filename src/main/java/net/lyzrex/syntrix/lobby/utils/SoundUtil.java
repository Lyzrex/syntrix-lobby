package net.lyzrex.syntrix.lobby.utils;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


public final class SoundUtil {

    private static final Method SOUND_KEY_METHOD = resolveKeyMethod("key");
    private static final Method SOUND_GET_KEY_METHOD = resolveKeyMethod("getKey");

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

            Sound alias = AliasLookupHolder.lookup(candidate);
            if (alias != null) {
                return alias;
            }
        }

        return valueOfFallback(in, fallback);
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

    private static final class AliasLookupHolder {
        private static final Map<String, Sound> ALIASES = buildAliasLookup();

        private AliasLookupHolder() {}

        private static @Nullable Sound lookup(String candidate) {
            String normalized = normalize(candidate);
            return normalized != null ? ALIASES.get(normalized) : null;
        }
    }

    private static Map<String, Sound> buildAliasLookup() {
        Map<String, Sound> map = new HashMap<>();

        for (Sound sound : availableSounds()) {
            NamespacedKey key = resolveNamespacedKey(sound);


            if (key == null) {
                continue;
            }

            String namespace = key.getNamespace();
            String value = key.getKey();

            registerAliasFamily(map, sound, value);
            registerAliasFamily(map, sound, namespace + ":" + value);
        }

        return map;
    }

    private static void registerAliasFamily(Map<String, Sound> map, Sound sound, String alias) {
        if (alias == null) {
            return;
        }
        registerAliasFamily(map, sound, Collections.singleton(alias));
    }

    private static void registerAliasFamily(Map<String, Sound> map, Sound sound, Collection<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return;
        }

        Deque<String> pending = new ArrayDeque<>(aliases);
        Set<String> seen = new LinkedHashSet<>();

        while (!pending.isEmpty()) {
            String candidate = pending.removeFirst();
            if (!seen.add(candidate)) {
                continue;
            }

            String normalized = normalize(candidate);
            if (normalized == null) {
                continue;
            }

            registerAlias(map, sound, normalized);

            String withoutMinecraft = stripMinecraftPrefix(normalized);
            if (withoutMinecraft != null) {
                pending.addLast(withoutMinecraft);
            }
        }
    }

    private static void registerAlias(Map<String, Sound> map, Sound sound, String normalizedAlias) {
        if (map.putIfAbsent(normalizedAlias, sound) != null) {
            return;
        }

        String namespace = "minecraft";
        String value = normalizedAlias;
        int colonIndex = normalizedAlias.indexOf(':');
        if (colonIndex >= 0) {
            namespace = normalizedAlias.substring(0, colonIndex);
            value = normalizedAlias.substring(colonIndex + 1);
        }

        for (String variant : expandSeparators(value)) {
            addAlias(map, sound, variant);
            addAlias(map, sound, namespace + ":" + variant);
        }

        if (!"minecraft".equals(namespace)) {
            for (String variant : expandSeparators(value)) {
                addAlias(map, sound, "minecraft:" + variant);
            }
        }
    }

    private static void addAlias(Map<String, Sound> map, Sound sound, String alias) {
        String normalized = normalize(alias);
        if (normalized == null) {
            return;
        }
        map.putIfAbsent(normalized, sound);
    }

    private static Collection<String> expandSeparators(String value) {
        Deque<String> pending = new ArrayDeque<>();
        Set<String> results = new LinkedHashSet<>();
        pending.add(value);

        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!results.add(current)) {
                continue;
            }

            if (current.contains(".")) {
                pending.add(current.replace('.', '_'));
                pending.add(current.replace('.', '-'));
            }
            if (current.contains("_")) {
                pending.add(current.replace('_', '.'));
                pending.add(current.replace('_', '-'));
            }
            if (current.contains("-")) {
                pending.add(current.replace('-', '_'));
                pending.add(current.replace('-', '.'));
            }
        }

        return results;
    }

    private static Iterable<Sound> availableSounds() {
        Iterable<Sound> registrySounds = soundsFromRegistry();
        if (registrySounds != null) {
            return registrySounds;
        }

        Sound[] enumConstants = Sound.class.getEnumConstants();
        if (enumConstants != null) {
            return Arrays.asList(enumConstants);
        }

        return Collections.emptyList();
    }

    private static @Nullable Iterable<Sound> soundsFromRegistry() {
        try {
            Iterator<Sound> iterator = Registry.SOUNDS.iterator();
            if (iterator == null) {
                return null;
            }

            List<Sound> sounds = new ArrayList<>();
            iterator.forEachRemaining(sounds::add);
            return sounds;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static @Nullable NamespacedKey resolveNamespacedKey(Sound sound) {
        NamespacedKey key = invokeKeyMethod(SOUND_KEY_METHOD, sound);
        if (key != null) {
            return key;
        }

        key = invokeKeyMethod(SOUND_GET_KEY_METHOD, sound);
        if (key != null) {
            return key;
        }

        try {
            NamespacedKey registryKey = Registry.SOUNDS.getKey(sound);
            if (registryKey != null) {
                return registryKey;
            }
        } catch (Throwable ignored) {
            // Registry lookup unavailable on older servers.
        }

        return null;
    }

    private static @Nullable NamespacedKey invokeKeyMethod(@Nullable Method method, Sound sound) {
        if (method == null || sound == null) {
            return null;
        }

        try {
            Object result = method.invoke(sound);
            if (result instanceof NamespacedKey namespacedKey) {
                return namespacedKey;
            }
        } catch (Throwable ignored) {
            // Method unavailable or failed; continue to fallback.
        }

        return null;
    }

    private static @Nullable Method resolveKeyMethod(String name) {
        try {
            return Sound.class.getMethod(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Sound valueOfFallback(String input, Sound fallback) {
        Sound legacy = AliasLookupHolder.lookup(input);
        return legacy != null ? legacy : fallback;
    }

    private static @Nullable String normalize(@Nullable String alias) {
        if (alias == null) {
            return null;
        }

        String trimmed = alias.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static @Nullable String stripMinecraftPrefix(String alias) {
        if (alias.startsWith("minecraft:") && alias.length() > "minecraft:".length()) {
            return alias.substring("minecraft:".length());
        }
        return null;
    }
}
