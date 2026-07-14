package com.feed.client;

import com.feed.Feed;
import com.feed.data.Comment;
import com.feed.data.FeedResponse;
import com.feed.data.Post;
import com.feed.data.PostType;
import com.feed.data.SolveResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Async HTTP client for the Feed proxy. Callbacks run off-thread;
 * hop back with {@code Minecraft.getInstance().execute(...)}.
 */
public final class FeedApiClient {

    public static final FeedApiClient INSTANCE = new FeedApiClient();

    private static final Gson GSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Type FEED_TYPE = new TypeToken<FeedResponse>() {}.getType();

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

    private static final String PROXY_BASE =
            "https:" + "//feed-proxy.kakouto4elovek.workers.dev";

    private final HttpClient http = FeedHttp.CLIENT;

    private volatile String feedEtag;
    private final Map<String, String> commentEtags = new ConcurrentHashMap<>();

    public record ReactResult(Map<String, Integer> counts, String active) {}

    public record CommentsPage(List<Comment> comments, String nextBefore, boolean notModified) {
        public CommentsPage(List<Comment> comments, String nextBefore) {
            this(comments, nextBefore, false);
        }
    }

    public record TagCount(String tag, int count) {}

    public record Notif(String id, String postId, String postText,
                          String author, String text, String createdAt) {}

    public record NotificationsResult(List<Notif> notifications, String latest) {}

    public record FeedPage(FeedResponse body, boolean notModified) {}


    private FeedApiClient() {
    }

    private static String token() {
        byte[] b = new byte[TOKEN_OBF.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) (TOKEN_OBF[i] ^ 0x5A);
        }
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static void addPlayerIdentity(JsonObject body) {
        var user = Minecraft.getInstance().getUser();
        body.addProperty("author", user.getName());
        body.addProperty("uuid", user.getProfileId().toString());
    }

    public CompletableFuture<FeedPage> fetchFeed(String cursor, int limit, String tag, boolean mine) {
        return fetchFeed(cursor, limit, tag, mine, false);
    }

    public CompletableFuture<FeedPage> fetchFeed(String cursor, int limit, String tag, boolean mine, boolean fresh) {
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
        HttpRequest.Builder req = builder(URI.create(url.toString()))
                .timeout(REQUEST_TIMEOUT).GET();
        if (fresh && feedEtag != null && (cursor == null || cursor.isEmpty()) && !mine
                && (tag == null || tag.isEmpty())) {
            req.header("If-None-Match", feedEtag);
        }
        return http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 304) {
                        return new FeedPage(null, true);
                    }
                    resp.headers().firstValue("etag").ifPresent(e -> feedEtag = e);
                    FeedResponse body = GSON.fromJson(checkBody(resp), FEED_TYPE);
                    return new FeedPage(body, false);
                });
    }

    public CompletableFuture<Post> createPost(String text, PostType type) {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("type", type.id);
        addPlayerIdentity(body);
        return postRaw("/v1/posts", body.toString()).thenApply(resp -> {
            JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
            if (o.has("post")) {
                return GSON.fromJson(o.get("post"), Post.class);
            }
            return GSON.fromJson(resp, Post.class);
        });
    }

    public CompletableFuture<ReactResult> toggleReaction(String postId, String key) {
        JsonObject body = new JsonObject();
        body.addProperty("postId", postId);
        body.addProperty("key", key);
        addPlayerIdentity(body);
        return postRaw("/v1/react", body.toString()).thenApply(resp -> {
            JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
            Map<String, Integer> counts = GSON.fromJson(o.get("counts"),
                    new TypeToken<Map<String, Integer>>() {}.getType());
            return new ReactResult(counts, FeedUtil.jstr(o, "active"));
        });
    }

    public CompletableFuture<CommentsPage> fetchComments(String postId, String before) {
        return fetchComments(postId, before, false, false);
    }

    public CompletableFuture<CommentsPage> fetchComments(String postId, String before, boolean fresh) {
        return fetchComments(postId, before, fresh, false);
    }

    /** Live poll only — may return 304 when comments unchanged. */
    public CompletableFuture<CommentsPage> fetchCommentsIfCached(String postId) {
        return fetchComments(postId, null, true, true);
    }

    private CompletableFuture<CommentsPage> fetchComments(
            String postId, String before, boolean fresh, boolean useEtag) {
        StringBuilder path = new StringBuilder("/v1/posts/").append(postId).append("/comments");
        boolean first = true;
        if (before != null && !before.isEmpty()) {
            path.append(first ? "?" : "&").append("before=").append(before);
            first = false;
        }
        if (fresh) {
            path.append(first ? "?" : "&").append("fresh=1");
        }
        HttpRequest.Builder req = builder(URI.create(base() + path))
                .timeout(REQUEST_TIMEOUT).GET();
        if (fresh && before == null && useEtag) {
            String etag = commentEtags.get(postId);
            if (etag != null) {
                req.header("If-None-Match", etag);
            }
        }
        return http.sendAsync(req.build(), HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    if (resp.statusCode() == 304) {
                        return new CommentsPage(null, null, true);
                    }
                    resp.headers().firstValue("etag").ifPresent(e -> commentEtags.put(postId, e));
                    JsonObject o = JsonParser.parseString(checkBody(resp)).getAsJsonObject();
                    List<Comment> list = GSON.fromJson(o.get("comments"),
                            new TypeToken<List<Comment>>() {}.getType());
                    return new CommentsPage(list, FeedUtil.jstr(o, "nextBefore"), false);
                });
    }

    public CompletableFuture<Comment> createComment(String postId, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        addPlayerIdentity(body);
        return postRaw("/v1/posts/" + postId + "/comments", body.toString())
                .thenApply(resp -> {
                    JsonObject o = JsonParser.parseString(resp).getAsJsonObject();
                    JsonObject c = o.has("comment") ? o.getAsJsonObject("comment") : o;
                    Comment comment = GSON.fromJson(c, Comment.class);
                    if (comment != null) {
                        comment.fromMod = true;
                    }
                    return comment;
                });
    }

    public CompletableFuture<Void> deletePost(String postId) {
        JsonObject body = new JsonObject();
        addPlayerIdentity(body);
        return deleteRaw("/v1/posts/" + postId, body.toString());
    }

    public CompletableFuture<Void> deleteComment(String postId, String commentId) {
        JsonObject body = new JsonObject();
        addPlayerIdentity(body);
        return deleteRaw("/v1/posts/" + postId + "/comments/" + commentId, body.toString());
    }

    public CompletableFuture<Void> report(String postId, String commentId, String reason) {
        JsonObject body = new JsonObject();
        body.addProperty("postId", postId);
        if (commentId != null) {
            body.addProperty("commentId", commentId);
        }
        if (reason != null) {
            body.addProperty("reason", reason);
        }
        addPlayerIdentity(body);
        return postRaw("/v1/report", body.toString()).thenApply(r -> null);
    }

    public CompletableFuture<SolveResult> solve(String postId) {
        JsonObject body = new JsonObject();
        addPlayerIdentity(body);
        return postRaw("/v1/posts/" + postId + "/solve", body.toString())
                .thenApply(resp -> GSON.fromJson(resp, SolveResult.class));
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
            JsonObject o = JsonParser.parseString(bodyStr).getAsJsonObject();
            List<Notif> list = GSON.fromJson(o.get("notifications"),
                    new TypeToken<List<Notif>>() {}.getType());
            return new NotificationsResult(list, FeedUtil.jstr(o, "latest"));
        });
    }

    public CompletableFuture<List<TagCount>> fetchTags(String q) {
        String path = "/v1/tags";
        if (q != null && !q.isBlank()) {
            path += "?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
        }
        return getRaw(path).thenApply(bodyStr -> {
            JsonObject o = JsonParser.parseString(bodyStr).getAsJsonObject();
            return GSON.fromJson(o.get("tags"), new TypeToken<List<TagCount>>() {}.getType());
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

    private CompletableFuture<Void> deleteRaw(String path, String body) {
        return http.sendAsync(builder(URI.create(base() + path))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> {
                    checkBody(resp);
                    return null;
                });
    }

    private static HttpRequest.Builder builder(URI uri) {
        return HttpRequest.newBuilder(uri).header("X-Feed-Token", token());
    }

    public static FeedApiException unwrap(Throwable ex) {
        while (ex.getCause() != null && !(ex instanceof FeedApiException)) {
            ex = ex.getCause();
        }
        return ex instanceof FeedApiException fae ? fae : new FeedApiException(ex);
    }

    private String checkBody(HttpResponse<String> resp) {
        int code = resp.statusCode();
        if (code < 200 || code >= 300) {
            throw new CompletionException(
                    new FeedApiException(code, errorMessage(resp.body())));
        }
        return resp.body();
    }

    private static String base() {
        return PROXY_BASE;
    }

    private static String errorMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonObject map = JsonParser.parseString(body).getAsJsonObject();
            if (map.has("error") && !map.get("error").isJsonNull()) {
                return map.get("error").getAsString();
            }
        } catch (Exception e) {
            Feed.LOG.debug("error parse failed", e);
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }
}
