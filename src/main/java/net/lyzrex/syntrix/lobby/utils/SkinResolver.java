package net.lyzrex.syntrix.lobby.utils;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.lyzrex.syntrix.lobby.SyntrixLobby;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class SkinResolver {

    private final SyntrixLobby plugin;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public SkinResolver(@NotNull SyntrixLobby plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<PlayerProfile> resolveByName(@NotNull String name) {
        return CompletableFuture.supplyAsync(() -> {
            PlayerProfile paper = tryPaperFirst(name);
            if (hasTextures(paper)) return paper;

            try {
                UUID uuid = fetchUuid(name);
                if (uuid == null) return paper;

                SignedTextures tx = fetchSignedTextures(uuid);
                if (tx == null) return paper;

                PlayerProfile prof = Bukkit.createProfile(uuid, name);
                prof.setProperty(new ProfileProperty("textures", tx.value(), tx.signature()));
                return prof;
            } catch (Exception ex) {
                plugin.getLogger().warning("[Skull] Mojang resolve failed for '" + name + "': " + ex.getMessage());
                return paper;
            }
        });
    }

    private PlayerProfile tryPaperFirst(String name) {
        try {
            PlayerProfile base;
            var off = Bukkit.getOfflinePlayerIfCached(name);
            if (off != null && off.getUniqueId() != null) {
                base = Bukkit.createProfile(off.getUniqueId(), name);
            } else {
                base = Bukkit.createProfile((UUID) null, name);
            }
            PlayerProfile updated = base.update().get(2500, TimeUnit.MILLISECONDS);
            return (updated != null) ? updated : base;
        } catch (Exception ignored) {
            return Bukkit.createProfile((UUID) null, name);
        }
    }

    private boolean hasTextures(@Nullable PlayerProfile p) {
        if (p == null) return false;
        return p.getProperties().stream()
                .anyMatch(prop -> "textures".equalsIgnoreCase(prop.getName())
                        && prop.getValue() != null && !prop.getValue().isBlank());
    }

    private @Nullable UUID fetchUuid(@NotNull String name) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "SyntrixLobby/1.0 (UUID-lookup)")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200 || res.body() == null || res.body().isBlank()) return null;

        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
        if (!json.has("id")) return null;
        String raw = json.get("id").getAsString();
        String dashed = raw.replaceFirst("(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)", "$1-$2-$3-$4-$5");
        return UUID.fromString(dashed);
    }

    private record SignedTextures(String value, String signature) {}

    private @Nullable SignedTextures fetchSignedTextures(@NotNull UUID uuid) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(
                        "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false"))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "SyntrixLobby/1.0 (Sessionserver)")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200 || res.body() == null || res.body().isBlank()) return null;

        JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
        if (!json.has("properties")) return null;
        JsonArray props = json.getAsJsonArray("properties");
        if (props.size() == 0) return null;

        JsonObject first = props.get(0).getAsJsonObject();
        String value = first.has("value") ? first.get("value").getAsString() : null;
        String sig = first.has("signature") ? first.get("signature").getAsString() : null;
        if (value == null || value.isBlank() || sig == null || sig.isBlank()) return null;

        return new SignedTextures(value, sig);
    }
}