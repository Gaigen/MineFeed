package com.feed.client;

import com.feed.data.Comment;
import com.feed.data.FeedResponse;
import com.feed.data.Post;
import com.feed.data.PostType;
import com.feed.data.SolveResult;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Async HTTP client for the Feed proxy. Callbacks run off-thread;
 * hop back with {@code Minecraft.getInstance().execute(...)}.
 */
public final class FeedApiClient {

    public static final FeedApiClient INSTANCE = new FeedApiClient();

    private static final Gson GSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    // XOR-obfuscated APP_TOKEN (must match Worker secret). Regenerate via TokenGen.
    private static final byte[] TOKEN_OBF = {
            0x2F, 0x3D, 0x23, 0x3B, 0x29, 0x3E, 0x32, 0x3C,
            0x68, 0x62, 0x6D, 0x6C, 0x69, 0x2E, 0x6D, 0x62, 0x6B,
            0x2B, 0x3D, 0x2D, 0x3E, 0x32, 0x3C, 0x3D, 0x3B,
            0x6D, 0x6C, 0x69, 0x2E, 0x2F, 0x23, 0x32, 0x2B,
            0x3E, 0x2F, 0x33, 0x23, 0x2B, 0x3C, 0x2E, 0x68,
            0x6D, 0x6C, 0x2C, 0x39, 0x29, 0x62, 0x23,
            0x28, 0x2E, 0x6B, 0x68, 0x69, 0x3D, 0x2F, 0x32,
            0x29, 0x2C, 0x22, 0x6D, 0x6C, 0x6B
    };

    // Split so `strings feed.jar` does not expose the full URL.
    private static final String PROXY_BASE =
            "https:" + "//feed-proxy.kakouto4elovek.workers.dev";

    private final HttpClient http = FeedHttp.CLIENT;

    public record ReactResult(Map<String, Integer> counts, String active) {}

    public record CommentsPage(List<Comment> comments, String nextBefore) {}

    public record TagCount(String tag, int count) {}

    public record Notif(String id, String postId, String postText,
                          String author, String text, String createdAt) {}

    public record NotificationsResult(List<Notif> notifications, String latest) {}

    private FeedApiClient() {
    }

    private static String token() {
        byte[] b = new byte[TOKEN_OBF.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (TOKEN_OBF[i] ^ 0x5A);
        }
        return new String(b, StandardCharsets.US_ASCII);
    }

    public CompletableFuture<FeedResponse> fetchFeed(String cursor, int limit, String tag, boolean mine) {
        return fetchFeed(cursor, limit, tag, mine, false);
    }

    public CompletableFuture<FeedResponse> fetchFeed(String cursor, int limit, String tag, boolean mine, boolean fresh) {
        StringBuilder url = new StringBuilder(base())
                .append("/v1/feed?limit=").append(limit)
                .append("&uuid=").append(Minecraft.getInstance().getUser().getProfileId().toString());
        if (cursor != null && !cursor.isEmpty()) {
            url.append("&cursor=").append(cursor);
        }
        if (tag != null && !tag.isEmpty()) {
            url.append("&tag=").append(URLEncoder.encode(tag, StandardCharsets.UTF_8));
        }
        if (mine) {
            url.append("&mine=1");
        }
        if (fresh) {
            url.append("&fresh=1");
        }
        return send(builder(URI.create(url.toString()))
                .timeout(REQUEST_TIMEOUT).GET().build(), FeedResponse.class);
    }

    public CompletableFuture<Post> createPost(String text, PostType type) {
        var user = Minecraft.getInstance().getUser();
        String body = GSON.toJson(Map.of(
                "author", user.getName(),
                "uuid", user.getProfileId().toString(),
                "text", text,
                "type", type.id));
        return postRaw("/v1/posts", body).thenApply(resp -> {
            JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
            if (o.has("post")) {
                return GSON.fromJson(o.get("post"), Post.class);
            }
            return GSON.fromJson(resp, Post.class);
        });
    }

    public CompletableFuture<ReactResult> toggleReaction(String postId, String uuid, String key) {
        JsonObject body = new JsonObject();
        body.addProperty("postId", postId);
        body.addProperty("uuid", uuid);
        body.addProperty("key", key);
        return postRaw("/v1/react", body.toString()).thenApply(resp -> {
            try {
                JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
                Map<String, Integer> counts = new HashMap<>();
                for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("counts").entrySet()) {
                    counts.put(e.getKey(), e.getValue().getAsInt());
                }
                return new ReactResult(counts, jstr(o, "active"));
            } catch (Exception e) {
                return null;
            }
        });
    }

    public CompletableFuture<CommentsPage> fetchComments(String postId, String before) {
        return fetchComments(postId, before, false);
    }

    public CompletableFuture<CommentsPage> fetchComments(String postId, String before, boolean fresh) {
        StringBuilder path = new StringBuilder("/v1/posts/").append(postId).append("/comments");
        boolean first = true;
        if (before != null && !before.isEmpty()) {
            path.append(first ? "?" : "&").append("before=").append(before);
            first = false;
        }
        if (fresh) {
            path.append(first ? "?" : "&").append("fresh=1");
        }
        return getRaw(path.toString()).thenApply(bodyStr -> {
            try {
                JsonObject o = JsonParser.parseString(bodyStr).getAsJsonObject();
                List<Comment> list = new ArrayList<>();
                for (JsonElement el : o.getAsJsonArray("comments")) {
                    JsonObject c = el.getAsJsonObject();
                    Comment comment = new Comment();
                    comment.id = jstr(c, "id");
                    comment.author = jstr(c, "author");
                    comment.text = jstr(c, "text");
                    comment.createdAt = jstr(c, "createdAt");
                    comment.fromMod = c.has("fromMod") && c.get("fromMod").getAsBoolean();
                    list.add(comment);
                }
                return new CommentsPage(list, jstr(o, "nextBefore"));
            } catch (Exception e) {
                return new CommentsPage(List.of(), null);
            }
        });
    }

    public CompletableFuture<Comment> createComment(String postId, String text) {
        var user = Minecraft.getInstance().getUser();
        String body = GSON.toJson(Map.of(
                "author", user.getName(),
                "uuid", user.getProfileId().toString(),
                "text", text));
        return postRaw("/v1/posts/" + postId + "/comments", body).thenApply(resp -> {
            JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
            JsonObject c = o.has("comment") ? o.getAsJsonObject("comment") : o;
            Comment comment = GSON.fromJson(c, Comment.class);
            if (comment != null) {
                comment.fromMod = true;
            }
            return comment;
        });
    }

    public CompletableFuture<SolveResult> solve(String postId) {
        String body = GSON.toJson(Map.of(
                "uuid", Minecraft.getInstance().getUser().getProfileId().toString()));
        String path = "/v1/posts/" + postId + "/solve";
        return send(builder(URI.create(base() + path))
                .timeout(REQUEST_TIMEOUT).POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build(), SolveResult.class);
    }

    public CompletableFuture<Post> fetchPost(String postId) {
        String uuid = Minecraft.getInstance().getUser().getProfileId().toString();
        return getRaw("/v1/posts/" + postId + "?uuid=" + uuid).thenApply(bodyStr -> {
            JsonObject o = JsonParser.parseString(bodyStr).getAsJsonObject();
            return GSON.fromJson(o.get("post"), Post.class);
        });
    }

    public CompletableFuture<NotificationsResult> fetchNotifications(String uuid, String after) {
        String path = "/v1/notifications?uuid=" + uuid
                + (after == null || after.isEmpty() ? "" : "&after=" + after);
        return getRaw(path).thenApply(bodyStr -> {
            try {
                JsonObject o = JsonParser.parseString(bodyStr).getAsJsonObject();
                List<Notif> list = new ArrayList<>();
                for (JsonElement el : o.getAsJsonArray("notifications")) {
                    JsonObject n = el.getAsJsonObject();
                    list.add(new Notif(jstr(n, "id"), jstr(n, "postId"), jstr(n, "postText"),
                            jstr(n, "author"), jstr(n, "text"), jstr(n, "createdAt")));
                }
                return new NotificationsResult(list, jstr(o, "latest"));
            } catch (Exception e) {
                return null;
            }
        });
    }

    public CompletableFuture<List<TagCount>> fetchTags(String q) {
        String path = "/v1/tags";
        if (q != null && !q.isBlank()) {
            path += "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
        }
        return getRaw(path).thenApply(bodyStr -> {
            try {
                JsonObject o = JsonParser.parseString(bodyStr).getAsJsonObject();
                List<TagCount> list = new ArrayList<>();
                for (JsonElement el : o.getAsJsonArray("tags")) {
                    JsonObject t = el.getAsJsonObject();
                    list.add(new TagCount(jstr(t, "tag"), t.get("count").getAsInt()));
                }
                return list;
            } catch (Exception e) {
                return List.of();
            }
        });
    }

    private CompletableFuture<String> getRaw(String path) {
        return http.sendAsync(builder(URI.create(base() + path))
                        .timeout(REQUEST_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString()).thenApply(this::checkBody);
    }

    private CompletableFuture<String> postRaw(String path, String body) {
        return http.sendAsync(builder(URI.create(base() + path))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString()).thenApply(this::checkBody);
    }

    private static HttpRequest.Builder builder(URI uri) {
        return HttpRequest.newBuilder(uri).header("X-Feed-Token", token());
    }

    /** Unwraps CompletionException to FeedApiException when present. */
    public static FeedApiException unwrap(Throwable ex) {
        while (ex.getCause() != null && !(ex instanceof FeedApiException)) {
            ex = ex.getCause();
        }
        return ex instanceof FeedApiException fae ? fae : new FeedApiException(ex);
    }

    private <T> CompletableFuture<T> send(HttpRequest request, Class<T> type) {
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    String body = checkBody(resp);
                    T parsed = GSON.fromJson(body, type);
                    if (parsed == null) {
                        throw new CompletionException(
                                new FeedApiException(resp.statusCode(), "empty response body"));
                    }
                    return parsed;
                });
    }

    private String checkBody(HttpResponse<String> resp) {
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            throw new CompletionException(
                    new FeedApiException(code, errorMessage(resp.body())));
        }
        return resp.body();
    }

    private static String jstr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static String base() {
        return PROXY_BASE;
    }

    private static String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            Map<?, ?> map = GSON.fromJson(body, Map.class);
            if (map != null) {
                if (map.get("detail") instanceof String d && !d.isBlank()) {
                    return d.length() > 160 ? d.substring(0, 160) : d;
                }
                if (map.get("error") instanceof String s) {
                    return s;
                }
            }
        } catch (Exception ignored) {
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
