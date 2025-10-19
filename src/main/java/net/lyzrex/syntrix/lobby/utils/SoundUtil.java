package net.lyzrex.syntrix.lobby.utils;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class SoundUtil {

    private SoundUtil() {}

    public static @NotNull Sound resolve(@Nullable String raw, @NotNull Sound fallback) {
        if (raw == null) return fallback;
        String in = raw.trim();
        if (in.isEmpty()) return fallback;

        for (String candidate : buildCandidates(in)) {
            Sound s = byRegistryKey(candidate);
            if (s != null) return s;
        }

        try {
            return Sound.valueOf(in.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static @Nullable Sound byRegistryKey(String keyStr) {
        String normalized = keyStr.toLowerCase();
        if (!normalized.contains(":")) normalized = "minecraft:" + normalized;

        NamespacedKey key = NamespacedKey.fromString(normalized);
        if (key == null) return null;

        try {
            return Registry.SOUNDS.get(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<String> buildCandidates(String input) {
        List<String> list = new ArrayList<>();
        String s = input.trim();

        if (s.contains(":") || s.contains(".")) {
            list.add(s);
            list.add(s.replace('.', '_'));
        }
        if (s.contains("_")) {
            list.add(s);
            list.add(s.replace('_', '.'));
        }
        if (isLikelyEnumName(s)) {
            String lowerDots = s.toLowerCase().replace('_', '.');
            list.add(lowerDots);
            list.add("minecraft:" + lowerDots);
        }
        if (!s.contains(":") && !s.contains(".") && !s.contains("_")) {
            list.add(s);
            list.add("minecraft:" + s);
        }
        String unifiedDots = s.toLowerCase().replace('-', '_').replace('_', '.');
        list.add(unifiedDots);
        list.add("minecraft:" + unifiedDots);

        return list;
    }

    private static boolean isLikelyEnumName(String s) {
        if (s.contains(".") || s.contains(":")) return false;
        if (!s.contains("_")) return false;
        boolean hasLetter = false;
        for (char c : s.toCharArray()) {
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) return false;
            }
        }
        return hasLetter;
    }
}
