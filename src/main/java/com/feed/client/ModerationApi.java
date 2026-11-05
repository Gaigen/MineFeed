package com.feed.client;

import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.ApiError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Reports and the moderation queue (/v2/reports, /v2/mod/*, /v2/users/:id/ban). */
public final class ModerationApi {

    /** Reasons the server accepts, in the order shown to players. */
    public static final String[] REASONS = {"spam", "abuse", "nsfw", "scam", "other"};
    /** Ban lengths in days; 0 = forever. */
    public static final int[] BAN_DAYS = {1, 7, 30, 0};

    public record Author(String id, String tag, String nick, String avatar) {}

    /** Open reports about one post or comment, grouped. */
    public record Report(String postId, String commentId, String space, String spaceName, int count, long lastAt,
                         List<String> reasons, List<String> reporters, List<String> details,
                         String text, boolean image, Author author, String type, boolean canBan, boolean canKick) {
        public String key() {
            return postId + "/" + (commentId == null ? "" : commentId);
        }
    }

    private ModerationApi() {
    }

    private static CompletableFuture<JsonObject> call(String method, String path, JsonObject body) {
        AccountApi api = AccountManager.INSTANCE.api();
        return api.call(method, path, body).handle((o, ex) -> {
            if (ex == null) {
                return o;
            }
            ApiError e = AccountApi.unwrap(ex);
            Minecraft.getInstance().execute(() -> AccountManager.INSTANCE.handleError(e));
            throw new CompletionException(e);
        });
    }

    /** Sends a report; true = new, false = this player already reported it. */
    public static CompletableFuture<Boolean> report(String postId, String commentId, String reason, String details) {
        JsonObject b = new JsonObject();
        b.addProperty("postId", postId);
        if (commentId != null) {
            b.addProperty("commentId", commentId);
        }
        b.addProperty("reason", reason);
        if (details != null && !details.isBlank()) {
            b.addProperty("details", details.trim());
        }
        return call("POST", "/v2/reports", b).thenApply(o -> !bool(o, "duplicate"));
    }

    public static CompletableFuture<List<Report>> reports() {
        return call("GET", "/v2/mod/reports", null).thenApply(o -> {
            List<Report> list = new ArrayList<>();
            for (JsonElement e : arr(o, "reports")) {
                JsonObject r = e.getAsJsonObject();
                JsonObject t = r.has("target") && r.get("target").isJsonObject() ? r.getAsJsonObject("target") : new JsonObject();
                JsonObject a = t.has("author") && t.get("author").isJsonObject() ? t.getAsJsonObject("author") : new JsonObject();
                list.add(new Report(str(r, "postId"), str(r, "commentId"), str(r, "space"), str(r, "spaceName"),
                        num(r, "count"), r.has("lastAt") && !r.get("lastAt").isJsonNull() ? r.get("lastAt").getAsLong() : 0L,
                        strings(r, "reasons"), strings(r, "reporters"), strings(r, "details"),
                        str(t, "text"), t.has("image") && t.get("image").isJsonObject(),
                        new Author(str(a, "id"), str(a, "tag"), str(a, "nick"), str(a, "avatar")),
                        str(t, "type"), bool(r, "canBan"), bool(r, "canKick")));
            }
            return list;
        });
    }

    /** action: dismiss | delete | kick */
    public static CompletableFuture<Void> resolve(String postId, String commentId, String action) {
        JsonObject b = new JsonObject();
        b.addProperty("postId", postId);
        if (commentId != null) {
            b.addProperty("commentId", commentId);
        }
        b.addProperty("action", action);
        return call("POST", "/v2/mod/resolve", b).thenApply(o -> null);
    }

    /** Server moderators only. days: 1, 7, 30 or 0 (forever). Returns the end time or null for forever. */
    public static CompletableFuture<Long> ban(String userId, int days, String reason, String postId, String commentId,
                                              boolean deleteContent) {
        JsonObject b = new JsonObject();
        b.addProperty("days", days);
        b.addProperty("reason", reason == null ? "" : reason.trim());
        if (postId != null) {
            b.addProperty("postId", postId);
            if (commentId != null) {
                b.addProperty("commentId", commentId);
            }
            b.addProperty("deleteContent", deleteContent);
        }
        return call("POST", "/v2/users/" + userId + "/ban", b)
                .thenApply(o -> o.has("until") && !o.get("until").isJsonNull() ? o.get("until").getAsLong() : null);
    }

    public static CompletableFuture<Void> unban(String userId) {
        return call("DELETE", "/v2/users/" + userId + "/ban", null).thenApply(o -> null);
    }

    /** Readable error text. */
    public static Component describe(Throwable ex) {
        ApiError e = AccountApi.unwrap(ex);
        String key = switch (e.code) {
            case "own_content" -> "feed.report.error.own";
            case "not_mod", "forbidden" -> "feed.mod.error.forbidden";
            case "is_mod" -> "feed.mod.error.is_mod";
            case "self" -> "feed.mod.error.self";
            default -> null;
        };
        return key != null ? Component.translatable(key) : CommunityApi.describe(ex);
    }

    // ---------- json ----------

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr(o, k)) {
            if (e.isJsonPrimitive()) {
                out.add(e.getAsString());
            }
        }
        return out;
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
