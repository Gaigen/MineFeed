package com.feed.client;

import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.ApiError;
import com.feed.client.screen.AccountUi;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** /v2/communities*: private communities, invite codes, members, applications. */
public final class CommunityApi {

    public record Community(String id, String name, String description, int members, String ownerId,
                            String role, int unread, int requests, String about, String links) {
        public boolean isMod() {
            return "owner".equals(role) || "mod".equals(role);
        }

        public boolean isOwner() {
            return "owner".equals(role);
        }
    }

    public record Application(String id, String name, String status, String reason, String communityId) {
        public boolean pending() {
            return "pending".equals(status);
        }
    }

    public record Pending(String id, String name) {}

    public record Mine(List<Community> communities, List<Pending> pending, Application application, boolean canApply) {}

    public record Code(String code, String note, int uses, Integer maxUses, Long expiresAt, boolean approval, boolean dead) {}

    public record Member(String id, String tag, String nick, String role, String via) {}

    public record JoinRequest(String id, String tag, String nick, String via, long createdAt) {}

    public record JoinResult(String status, Community community) {}

    private static volatile Mine cached;

    private CommunityApi() {
    }

    /** Last /v2/communities answer (null before the first load). */
    public static Mine cached() {
        return cached;
    }

    public static Community cachedById(String id) {
        Mine m = cached;
        if (m == null || id == null) {
            return null;
        }
        for (Community c : m.communities()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    private static AccountApi api() {
        return AccountManager.INSTANCE.api();
    }

    private static CompletableFuture<JsonObject> call(String method, String path, JsonObject body) {
        return api().call(method, path, body).handle((o, ex) -> {
            if (ex == null) {
                return o;
            }
            ApiError e = AccountApi.unwrap(ex);
            Minecraft.getInstance().execute(() -> AccountManager.INSTANCE.handleError(e));
            throw new CompletionException(e);
        });
    }

    // ---------- communities ----------

    public static CompletableFuture<Mine> mine() {
        return call("GET", "/v2/communities", null).thenApply(o -> {
            List<Community> list = new ArrayList<>();
            for (JsonElement e : arr(o, "communities")) {
                list.add(community(e.getAsJsonObject()));
            }
            List<Pending> pending = new ArrayList<>();
            for (JsonElement e : arr(o, "pending")) {
                JsonObject p = e.getAsJsonObject();
                pending.add(new Pending(str(p, "id"), str(p, "name")));
            }
            Application app = null;
            if (o.has("application") && o.get("application").isJsonObject()) {
                JsonObject a = o.getAsJsonObject("application");
                app = new Application(str(a, "id"), str(a, "name"), str(a, "status"), str(a, "reason"), str(a, "communityId"));
            }
            Mine m = new Mine(list, pending, app, bool(o, "canApply"));
            cached = m;
            return m;
        });
    }

    public static CompletableFuture<Community> update(String id, String name, String description, String about, String links) {
        JsonObject b = new JsonObject();
        b.addProperty("name", name);
        b.addProperty("description", description);
        b.addProperty("about", about);
        b.addProperty("links", links);
        return call("PATCH", "/v2/communities/" + id, b).thenApply(o -> community(o.getAsJsonObject("community")));
    }

    public static CompletableFuture<Void> remove(String id) {
        return call("DELETE", "/v2/communities/" + id, null).thenApply(o -> null);
    }

    public static CompletableFuture<Void> leave(String id) {
        return call("POST", "/v2/communities/" + id + "/leave", new JsonObject()).thenApply(o -> null);
    }

    public static CompletableFuture<JoinResult> join(String code) {
        JsonObject b = new JsonObject();
        b.addProperty("code", code);
        return call("POST", "/v2/join", b).thenApply(o -> new JoinResult(str(o, "status"), community(o.getAsJsonObject("community"))));
    }

    public static CompletableFuture<Application> apply(String name, String description, String about, String links) {
        JsonObject b = new JsonObject();
        b.addProperty("name", name);
        b.addProperty("description", description);
        b.addProperty("about", about);
        b.addProperty("links", links);
        b.addProperty("lang", Minecraft.getInstance().getLanguageManager().getSelected());
        return call("POST", "/v2/communities/applications", b).thenApply(o -> {
            JsonObject a = o.getAsJsonObject("application");
            return new Application(str(a, "id"), str(a, "name"), str(a, "status"), str(a, "reason"), str(a, "communityId"));
        });
    }

    // ---------- codes ----------

    public static CompletableFuture<List<Code>> codes(String id) {
        return call("GET", "/v2/communities/" + id + "/codes", null).thenApply(o -> {
            List<Code> list = new ArrayList<>();
            for (JsonElement e : arr(o, "codes")) {
                list.add(code(e.getAsJsonObject()));
            }
            return list;
        });
    }

    public static CompletableFuture<Code> createCode(String id, boolean approval, Integer maxUses, Integer expiresInDays) {
        JsonObject b = new JsonObject();
        b.addProperty("approval", approval);
        if (maxUses != null) {
            b.addProperty("maxUses", maxUses);
        }
        if (expiresInDays != null) {
            b.addProperty("expiresInDays", expiresInDays);
        }
        return call("POST", "/v2/communities/" + id + "/codes", b).thenApply(o -> code(o.getAsJsonObject("code")));
    }

    public static CompletableFuture<Void> revokeCode(String id, String code) {
        return call("DELETE", "/v2/communities/" + id + "/codes/" + code, null).thenApply(o -> null);
    }

    // ---------- members & requests ----------

    public static CompletableFuture<List<Member>> members(String id) {
        return call("GET", "/v2/communities/" + id + "/members", null).thenApply(o -> {
            List<Member> list = new ArrayList<>();
            for (JsonElement e : arr(o, "members")) {
                JsonObject m = e.getAsJsonObject();
                JsonObject a = m.getAsJsonObject("account");
                list.add(new Member(str(a, "id"), str(a, "tag"), str(a, "nick"), str(m, "role"), str(m, "via")));
            }
            return list;
        });
    }

    public static CompletableFuture<Void> setRole(String id, String accountId, String role) {
        JsonObject b = new JsonObject();
        b.addProperty("role", role);
        return call("PATCH", "/v2/communities/" + id + "/members/" + accountId, b).thenApply(o -> null);
    }

    public static CompletableFuture<Void> kick(String id, String accountId, boolean ban) {
        return call("DELETE", "/v2/communities/" + id + "/members/" + accountId + (ban ? "?ban=1" : ""), null).thenApply(o -> null);
    }

    public static CompletableFuture<List<JoinRequest>> requests(String id) {
        return call("GET", "/v2/communities/" + id + "/requests", null).thenApply(o -> {
            List<JoinRequest> list = new ArrayList<>();
            for (JsonElement e : arr(o, "requests")) {
                JsonObject r = e.getAsJsonObject();
                JsonObject a = r.getAsJsonObject("account");
                list.add(new JoinRequest(str(a, "id"), str(a, "tag"), str(a, "nick"), str(r, "via"),
                        r.has("createdAt") ? r.get("createdAt").getAsLong() : 0L));
            }
            return list;
        });
    }

    public static CompletableFuture<Void> decide(String id, String accountId, boolean approve) {
        JsonObject b = new JsonObject();
        b.addProperty("approve", approve);
        return call("POST", "/v2/communities/" + id + "/requests/" + accountId, b).thenApply(o -> null);
    }

    // ---------- errors ----------

    public static ApiError error(Throwable ex) {
        return AccountApi.unwrap(ex);
    }

    /** Readable text for an API error: own strings for known codes, otherwise the server message. */
    public static Component describe(Throwable ex) {
        ApiError e = error(ex);
        String key = switch (e.code) {
            case "discord_required" -> "feed.community.error.discord_required";
            case "not_in_guild" -> "feed.community.error.not_in_guild";
            case "already_applied" -> "feed.community.error.already_applied";
            case "limit" -> "feed.community.error.limit";
            case "bad_name" -> "feed.community.error.bad_name";
            case "bad_description" -> "feed.community.error.bad_description";
            case "applications_off" -> "feed.community.error.applications_off";
            case "bad_code", "no_code" -> "feed.community.error.no_code";
            case "full" -> "feed.community.error.full";
            case "code_expired", "code_used_up" -> "feed.community.error.code_dead";
            case "banned_here" -> "feed.community.error.banned_here";
            case "rate_limited" -> "feed.account.error.rate_limited";
            case "network" -> "feed.screen.error.network";
            default -> null;
        };
        if (key != null) {
            return Component.translatable(key);
        }
        if (e.getMessage() != null && !e.getMessage().isBlank() && e.status > 0) {
            return Component.literal(e.getMessage());
        }
        return AccountUi.describe(e);
    }

    // ---------- json ----------

    private static Community community(JsonObject o) {
        return new Community(str(o, "id"), str(o, "name"), str(o, "description"), num(o, "members"), str(o, "ownerId"),
                str(o, "role"), num(o, "unread"), num(o, "requests"), str(o, "about"), str(o, "links"));
    }

    private static Code code(JsonObject o) {
        return new Code(str(o, "code"), str(o, "note"), num(o, "uses"),
                o.has("maxUses") && !o.get("maxUses").isJsonNull() ? o.get("maxUses").getAsInt() : null,
                o.has("expiresAt") && !o.get("expiresAt").isJsonNull() ? o.get("expiresAt").getAsLong() : null,
                bool(o, "approval"), bool(o, "dead"));
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static int num(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsInt() : 0;
    }

    private static boolean bool(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }
}
