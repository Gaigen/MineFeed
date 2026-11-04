package com.feed.client.account;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * MineFeed API v2 (accounts). No Minecraft classes here, so it can be tested standalone.
 * The account key is only ever sent in the Authorization header and is never logged.
 */
public final class AccountApi {

    public static final String DEFAULT_BASE = "https:" + "//minefeed-dev.kakouto4elovek.workers.dev";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Gson GSON = new Gson();

    public record Account(String id, String tag, String nick, long createdAt,
                          boolean tagProtected, boolean discordLinked, Long canChangeTagAt) {}

    public record Session(Account account, String key, String recoveryCode) {}

    public record TagCheck(String tag, boolean available, String reason) {}

    public record Meta(int api, String minClient, String latestClient, String downloadUrl) {}

    private final HttpClient http;
    private final String base;
    private final String userAgent;
    private final Supplier<String> keySupplier;

    public AccountApi(HttpClient http, String base, String clientVersion, Supplier<String> keySupplier) {
        this.http = http;
        this.base = base.replaceAll("/+$", "");
        this.userAgent = "MineFeed/" + clientVersion;
        this.keySupplier = keySupplier;
    }

    public static String baseFromEnvironment() {
        String override = System.getProperty("minefeed.api");
        return override != null && override.startsWith("https://") ? override : DEFAULT_BASE;
    }

    public CompletableFuture<Meta> meta() {
        return send("GET", "/v2/meta", null, false).thenApply(j -> GSON.fromJson(j, Meta.class));
    }

    public CompletableFuture<TagCheck> checkTag(String tag) {
        return send("GET", "/v2/tags/check?tag=" + URLEncoder.encode(tag, StandardCharsets.UTF_8), null, false)
                .thenApply(j -> GSON.fromJson(j, TagCheck.class));
    }

    public CompletableFuture<Session> register(String tag, String nick) {
        JsonObject body = new JsonObject();
        body.addProperty("tag", tag);
        body.addProperty("nick", nick);
        return send("POST", "/v2/auth/register", body, false).thenApply(AccountApi::session);
    }

    public CompletableFuture<Session> recover(String recoveryCode) {
        JsonObject body = new JsonObject();
        body.addProperty("recoveryCode", recoveryCode);
        return send("POST", "/v2/auth/recover", body, false).thenApply(AccountApi::session);
    }

    public CompletableFuture<Account> me() {
        return send("GET", "/v2/me", null, true).thenApply(AccountApi::account);
    }

    public CompletableFuture<Account> setNick(String nick) {
        JsonObject body = new JsonObject();
        body.addProperty("nick", nick);
        return send("PATCH", "/v2/me", body, true).thenApply(AccountApi::account);
    }

    public CompletableFuture<Account> setTag(String tag) {
        JsonObject body = new JsonObject();
        body.addProperty("tag", tag);
        return send("POST", "/v2/me/tag", body, true).thenApply(AccountApi::account);
    }

    public CompletableFuture<String> newRecoveryCode() {
        return send("POST", "/v2/me/recovery", new JsonObject(), true)
                .thenApply(j -> j.getAsJsonObject().get("recoveryCode").getAsString());
    }

    public CompletableFuture<Void> logout() {
        return send("POST", "/v2/auth/logout", new JsonObject(), true).thenApply(j -> null);
    }

    private static Session session(JsonElement j) {
        JsonObject o = j.getAsJsonObject();
        return new Session(GSON.fromJson(o.get("account"), Account.class),
                o.get("key").getAsString(), o.get("recoveryCode").getAsString());
    }

    private static Account account(JsonElement j) {
        return GSON.fromJson(j.getAsJsonObject().get("account"), Account.class);
    }

    private CompletableFuture<JsonElement> send(String method, String path, JsonObject body, boolean auth) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(TIMEOUT)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json");
        if (auth) {
            String key = keySupplier.get();
            if (key == null || key.isEmpty()) {
                return CompletableFuture.failedFuture(new ApiError(401, "no_auth", "no key", null));
            }
            req.header("Authorization", "Bearer " + key);
        }
        if (body != null) {
            req.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(GSON.toJson(body)));
        } else {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString())
                .handle((resp, ex) -> {
                    if (ex != null) {
                        throw new CompletionException(new ApiError(ex));
                    }
                    return parse(resp);
                });
    }

    private static JsonElement parse(HttpResponse<String> resp) {
        int code = resp.statusCode();
        JsonElement json = null;
        try {
            json = JsonParser.parseString(resp.body() == null ? "" : resp.body());
        } catch (Exception ignored) {
            // non-JSON (e.g. Cloudflare error page)
        }
        if (code >= 200 && code < 300 && json != null && json.isJsonObject()) {
            return json;
        }
        if (json != null && json.isJsonObject()) {
            JsonObject o = json.getAsJsonObject();
            String err = o.has("error") && o.get("error").isJsonPrimitive() ? o.get("error").getAsString() : "";
            String c = o.has("code") && o.get("code").isJsonPrimitive() ? o.get("code").getAsString() : "";
            throw new CompletionException(new ApiError(code, c, err, o));
        }
        throw new CompletionException(new ApiError(code, "http_" + code, "", null));
    }

    public static ApiError unwrap(Throwable ex) {
        Throwable t = ex;
        while (t != null && !(t instanceof ApiError) && t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t instanceof ApiError e ? e : new ApiError(ex);
    }
}
