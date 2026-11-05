package com.feed.client;

import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.ApiError;
import com.feed.data.Comment;
import com.feed.data.FeedResponse;
import com.feed.data.Post;
import com.feed.data.PostType;
import com.feed.data.SolveResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Feed over MineFeed API v2 (account key, Discord forum behind the Worker).
 * Keeps the old method shapes so the current screens work unchanged; the new UI will
 * use /v2/sync directly. Callbacks run off-thread; hop back with Minecraft.execute.
 */
public final class FeedApiClient {

    public static final FeedApiClient INSTANCE = new FeedApiClient();

    public record ReactResult(Map<String, Integer> counts, String active) {}

    public record CommentsPage(List<Comment> comments, String nextBefore, boolean notModified) {
        public CommentsPage(List<Comment> comments, String nextBefore) {
            this(comments, nextBefore, false);
        }
    }

    public record TagCount(String tag, int count) {}

    public record Notif(String id, String postId, String postText,
                          String author, String text, String createdAt,
                          String kind, String communityId, String communityName,
                          String actorId, String actorTag, String avatar, boolean unread) {
        public Notif(String id, String postId, String postText, String author, String text, String createdAt) {
            this(id, postId, postText, author, text, createdAt, null, null, null, null, null, null, false);
        }
    }

    public record NotificationsResult(List<Notif> notifications, String latest) {}

    public record FeedPage(FeedResponse body, boolean notModified) {}

    /** Server time of the last full first-page load per space, used as the /v2/sync cursor. */
    private final Map<String, Long> feedSince = new ConcurrentHashMap<>();
    private final Map<String, Long> commentSince = new ConcurrentHashMap<>();

    private FeedApiClient() {
    }

    private static AccountApi api() {
        return AccountManager.INSTANCE.api();
    }

    /** Was this written by the signed-in account? */
    public static boolean isMine(String authorId) {
        AccountApi.Account a = AccountManager.INSTANCE.account();
        return authorId != null && a != null && authorId.equals(a.id());
    }

    /**
     * Avatar key for AvatarCache: what the server says ("skin:…" / "discord:…"), except for our
     * own posts, where the local account (or the local skin) shows changes right away.
     */
    public static String avatarKey(String authorId, String avatar) {
        if (isMine(authorId)) {
            AccountApi.Account a = AccountManager.INSTANCE.account();
            if (a != null && a.avatar() != null) {
                return a.avatar();
            }
            if (a != null && "none".equals(a.avatarChoice())) {
                return null;
            }
            return "skin:" + Minecraft.getInstance().getUser().getProfileId().toString().replace("-", "");
        }
        return avatar;
    }

    private CompletableFuture<JsonObject> call(String method, String path, JsonObject body) {
        return api().call(method, path, body).handle((o, ex) -> {
            if (ex == null) {
                return o;
            }
            ApiError e = AccountApi.unwrap(ex);
            Minecraft.getInstance().execute(() -> AccountManager.INSTANCE.handleError(e));
            throw new CompletionException(e.isNetwork() ? new FeedApiException(e)
                    : new FeedApiException(e.status, "banned".equals(e.code)
                    ? com.feed.client.screen.AccountUi.describe(e).getString() : e.getMessage()));
        });
    }

    // ---------- feed ----------

    public CompletableFuture<FeedPage> fetchFeed(String space, String cursor, int limit, boolean mine, boolean fresh) {
        return fetchFeed(space, null, cursor, limit, mine, fresh);
    }

    /**
     * One page of a space, optionally only posts with #hashtag.
     * fresh = live poll of the first page: asks /v2/sync first and reloads only on changes.
     */
    public CompletableFuture<FeedPage> fetchFeed(String space, String hashtag, String cursor, int limit, boolean mine, boolean fresh) {
        String sp = space == null ? "global" : space;
        String key = hashtag == null ? sp : sp + "#" + hashtag;
        boolean first = cursor == null || cursor.isEmpty();
        Long since = feedSince.get(key);
        if (fresh && first && since != null && since > 0) {
            return call("GET", "/v2/sync?space=" + sp + "&since=" + since, null).thenCompose(s -> {
                boolean changed = bool(s, "reset") || s.getAsJsonArray("posts").size() > 0;
                if (!changed) {
                    feedSince.put(key, s.get("now").getAsLong());
                    return CompletableFuture.completedFuture(new FeedPage(null, true));
                }
                return loadFeed(sp, hashtag, key, null, limit, mine);
            });
        }
        return loadFeed(sp, hashtag, key, cursor, limit, mine);
    }

    // ---------- hashtags ----------

    public record HashtagCount(String tag, int posts) {}

    /** Popular tags of the space (empty prefix) or tags starting with the prefix. */
    public CompletableFuture<List<HashtagCount>> hashtags(String space, String prefix) {
        StringBuilder path = new StringBuilder("/v2/hashtags?space=").append(space == null ? "global" : space);
        if (prefix != null && !prefix.isEmpty()) {
            path.append("&q=").append(java.net.URLEncoder.encode(prefix, java.nio.charset.StandardCharsets.UTF_8));
        }
        return call("GET", path.toString(), null).thenApply(o -> {
            List<HashtagCount> list = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("tags")) {
                JsonObject t = e.getAsJsonObject();
                list.add(new HashtagCount(str(t, "tag"), t.get("posts").getAsInt()));
            }
            return list;
        });
    }

    // ---------- players ----------

    public record UserCard(String id, String tag, String nick, String avatar) {}

    public record Profile(String id, String tag, String nick, String avatar, long createdAt, boolean discord, boolean moderator,
                          int posts, int comments, int reactions, int gg, int solved,
                          boolean banned, Long banUntil, String banReason) {}

    /** Up to 10 players by @tag prefix or nick. */
    public CompletableFuture<List<UserCard>> searchUsers(String q) {
        String enc = java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8);
        return call("GET", "/v2/users?q=" + enc, null).thenApply(o -> {
            List<UserCard> list = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("users")) {
                JsonObject u = e.getAsJsonObject();
                list.add(new UserCard(str(u, "id"), str(u, "tag"), str(u, "nick"), str(u, "avatar")));
            }
            return list;
        });
    }

    /** Public profile by account id or @tag. */
    public CompletableFuture<Profile> fetchProfile(String idOrTag) {
        String enc = java.net.URLEncoder.encode(idOrTag, java.nio.charset.StandardCharsets.UTF_8);
        return call("GET", "/v2/users/" + enc, null).thenApply(o -> {
            JsonObject u = o.getAsJsonObject("user");
            JsonObject st = u.getAsJsonObject("stats");
            return new Profile(str(u, "id"), str(u, "tag"), str(u, "nick"), str(u, "avatar"), u.get("createdAt").getAsLong(),
                    bool(u, "discord"), bool(u, "moderator"), st.get("posts").getAsInt(), st.get("comments").getAsInt(),
                    st.get("reactions").getAsInt(), st.get("gg").getAsInt(), st.get("solved").getAsInt(),
                    bool(u, "banned"), u.has("banUntil") && !u.get("banUntil").isJsonNull() ? u.get("banUntil").getAsLong() : null,
                    str(u, "banReason"));
        });
    }

    /** Posts of one player in the global feed (profile view). */
    public CompletableFuture<FeedPage> fetchAuthorFeed(String authorId, String cursor, int limit) {
        StringBuilder path = new StringBuilder("/v2/feed?space=global&limit=").append(limit).append("&author=").append(authorId);
        if (cursor != null && !cursor.isEmpty()) {
            path.append("&before=").append(cursor);
        }
        return call("GET", path.toString(), null).thenApply(o -> {
            FeedResponse r = new FeedResponse();
            r.posts = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("posts")) {
                r.posts.add(toPost(e.getAsJsonObject()));
            }
            r.nextCursor = str(o, "next");
            return new FeedPage(r, false);
        });
    }

    private CompletableFuture<FeedPage> loadFeed(String space, String hashtag, String sinceKey, String cursor, int limit, boolean mine) {
        StringBuilder path = new StringBuilder("/v2/feed?space=").append(space).append("&limit=").append(limit);
        if (hashtag != null) {
            path.append("&tag=").append(java.net.URLEncoder.encode(hashtag, java.nio.charset.StandardCharsets.UTF_8));
        }
        if (cursor != null && !cursor.isEmpty()) {
            path.append("&before=").append(cursor);
        }
        if (mine) {
            path.append("&type=mine");
        }
        boolean first = cursor == null || cursor.isEmpty();
        return call("GET", path.toString(), null).thenApply(o -> {
            if (first) {
                feedSince.put(sinceKey, o.get("now").getAsLong());
            }
            FeedResponse r = new FeedResponse();
            r.posts = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("posts")) {
                r.posts.add(toPost(e.getAsJsonObject()));
            }
            r.nextCursor = str(o, "next");
            return new FeedPage(r, false);
        });
    }

    public CompletableFuture<Post> createPost(String text, PostType type) {
        return createPost("global", text, type);
    }

    public CompletableFuture<Post> createPost(String space, String text, PostType type) {
        return createPost(space, text, type, null);
    }

    /** imageJpeg: base64 JPEG from PostImages.encodeForUpload, or null. */
    public CompletableFuture<Post> createPost(String space, String text, PostType type, String imageJpeg) {
        JsonObject body = new JsonObject();
        body.addProperty("space", space == null ? "global" : space);
        body.addProperty("type", type.id);
        body.addProperty("text", text);
        if (imageJpeg != null) {
            JsonObject img = new JsonObject();
            img.addProperty("type", "image/jpeg");
            img.addProperty("data", imageJpeg);
            body.add("image", img);
        }
        return call("POST", "/v2/posts", body).thenApply(o -> toPost(o.getAsJsonObject("post")));
    }

    public CompletableFuture<ReactResult> toggleReaction(Post post, String key) {
        List<String> keys = post.postType().reactions();
        int slot = keys.indexOf(key);
        if (slot < 0) {
            return CompletableFuture.failedFuture(new FeedApiException(400, "bad_reaction"));
        }
        JsonObject body = new JsonObject();
        body.addProperty("slot", slot);
        return call("POST", "/v2/posts/" + post.id + "/react", body).thenApply(o -> {
            JsonArray re = o.getAsJsonArray("reactions");
            Map<String, Integer> counts = new HashMap<>();
            counts.put(keys.get(0), re.get(0).getAsInt());
            counts.put(keys.get(1), re.get(1).getAsInt());
            int my = o.get("my").getAsInt();
            return new ReactResult(counts, my >= 0 ? keys.get(my) : null);
        });
    }

    public CompletableFuture<Post> fetchPost(String postId) {
        return call("GET", "/v2/posts/" + postId, null).thenApply(o -> toPost(o.getAsJsonObject("post")));
    }

    // ---------- comments ----------

    public CompletableFuture<CommentsPage> fetchComments(String postId, String before) {
        return fetchComments(postId, before, false);
    }

    public CompletableFuture<CommentsPage> fetchComments(String postId, String before, boolean fresh) {
        if (before == null || before.isEmpty()) {
            return call("GET", "/v2/posts/" + postId, null).thenApply(o -> {
                commentSince.put(postId, o.get("now").getAsLong());
                return new CommentsPage(comments(o), str(o, "before"), false);
            });
        }
        return call("GET", "/v2/posts/" + postId + "/comments?before=" + before, null)
                .thenApply(o -> new CommentsPage(comments(o), str(o, "before"), false));
    }

    /** Live poll: only new comments since the last load (notModified when none). */
    public CompletableFuture<CommentsPage> fetchCommentsIfCached(String postId) {
        Long since = commentSince.get(postId);
        if (since == null) {
            return fetchComments(postId, null, true);
        }
        return call("GET", "/v2/sync?space=global&since=" + since + "&post=" + postId, null).thenApply(s -> {
            commentSince.put(postId, s.get("now").getAsLong());
            List<Comment> fresh = new ArrayList<>();
            for (JsonElement e : s.getAsJsonArray("comments")) {
                JsonObject c = e.getAsJsonObject();
                if (!bool(c, "deleted")) {
                    fresh.add(toComment(c));
                }
            }
            return fresh.isEmpty() ? new CommentsPage(null, null, true) : new CommentsPage(fresh, null, false);
        });
    }

    public CompletableFuture<Comment> createComment(String postId, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        return call("POST", "/v2/posts/" + postId + "/comments", body)
                .thenApply(o -> toComment(o.getAsJsonObject("comment")));
    }

    public CompletableFuture<Void> deletePost(String postId) {
        return call("DELETE", "/v2/posts/" + postId, null).thenApply(o -> null);
    }

    public CompletableFuture<Void> deleteComment(String postId, String commentId) {
        return call("DELETE", "/v2/comments/" + commentId, null).thenApply(o -> null);
    }

    public CompletableFuture<SolveResult> solve(String postId) {
        return call("POST", "/v2/posts/" + postId + "/solve", new JsonObject()).thenApply(o -> {
            SolveResult r = new SolveResult();
            r.ok = true;
            r.post = toPost(o.getAsJsonObject("post"));
            return r;
        });
    }

    // ---------- notifications ----------

    /** Newest first; {@code after} is filtered by the poller itself. uuid is unused in v2. */
    public CompletableFuture<NotificationsResult> fetchNotifications(String uuid, String after) {
        return call("GET", "/v2/notifications", null).thenApply(o -> {
            List<Notif> list = new ArrayList<>();
            long latest = 0;
            for (JsonElement e : o.getAsJsonArray("items")) {
                JsonObject n = e.getAsJsonObject();
                long id = n.get("id").getAsLong();
                latest = Math.max(latest, id);
                JsonObject actor = n.getAsJsonObject("actor");
                String kind = str(n, "kind");
                String community = str(n, "communityName");
                String raw = str(n, "text");
                String postText = str(n, "postText");
                String text = switch (kind == null ? "" : kind) {
                    case "react" -> Component.translatable("feed.notif.react", n.get("count").getAsInt()).getString();
                    case "request" -> Component.translatable("feed.notif.request", nz(community, raw)).getString();
                    case "accepted" -> Component.translatable("feed.notif.accepted", nz(community, raw)).getString();
                    case "app_approved" -> Component.translatable("feed.notif.app_approved", nz(community, raw)).getString();
                    case "app_rejected" -> Component.translatable("feed.notif.app_rejected", nz(raw, "-")).getString();
                    default -> raw;
                };
                if (postText == null && community != null) {
                    postText = community;
                }
                list.add(new Notif(String.valueOf(id), str(n, "postId"), postText,
                        actorName(actor), text, iso(n.get("updatedAt").getAsLong()),
                        kind, str(n, "communityId"), community,
                        actor == null ? null : str(actor, "id"), actor == null ? null : str(actor, "tag"),
                        actor == null ? null : str(actor, "avatar"), bool(n, "unread")));
            }
            return new NotificationsResult(list, latest > 0 ? String.valueOf(latest) : null);
        });
    }

    /** Hashtags are gone in v2. */
    public CompletableFuture<List<TagCount>> fetchTags(String q) {
        return CompletableFuture.completedFuture(List.of());
    }

    // ---------- mapping ----------

    private static Post toPost(JsonObject o) {
        Post p = new Post();
        p.id = str(o, "id");
        p.type = str(o, "type");
        p.text = str(o, "text");
        JsonObject a = o.getAsJsonObject("author");
        p.author = actorName(a);
        p.fromDiscord = bool(a, "discord");
        p.uuid = str(a, "id");
        p.authorTag = str(a, "tag");
        p.avatar = str(a, "avatar");
        p.createdAt = iso(o.get("createdAt").getAsLong());
        p.commentCount = o.get("comments").getAsInt();
        List<String> keys = p.postType().reactions();
        JsonArray re = o.getAsJsonArray("reactions");
        p.reactions.put(keys.get(0), re.get(0).getAsInt());
        p.reactions.put(keys.get(1), re.get(1).getAsInt());
        int my = o.has("my") ? o.get("my").getAsInt() : -1;
        p.myReaction = my >= 0 && my < 2 ? keys.get(my) : null;
        p.tags = List.of();
        p.solved = bool(o, "solved");
        p.space = str(o, "space");
        JsonObject img = firstImage(o);
        if (img != null) {
            p.imageUrl = str(img, "url");
            p.imageW = img.has("w") ? img.get("w").getAsInt() : 0;
            p.imageH = img.has("h") ? img.get("h").getAsInt() : 0;
        }
        return p;
    }

    private static List<Comment> comments(JsonObject o) {
        List<Comment> list = new ArrayList<>();
        for (JsonElement e : o.getAsJsonArray("comments")) {
            list.add(toComment(e.getAsJsonObject()));
        }
        return list;
    }

    private static Comment toComment(JsonObject o) {
        Comment c = new Comment();
        c.id = str(o, "id");
        c.text = str(o, "text");
        JsonObject a = o.getAsJsonObject("author");
        c.author = actorName(a);
        c.fromMod = !bool(a, "discord");
        c.authorId = str(a, "id");
        c.authorTag = str(a, "tag");
        c.avatar = str(a, "avatar");
        c.createdAt = iso(o.get("createdAt").getAsLong());
        JsonObject img = firstImage(o);
        if (img != null) {
            c.imageUrl = str(img, "url");
            c.imageW = img.has("w") ? img.get("w").getAsInt() : 0;
            c.imageH = img.has("h") ? img.get("h").getAsInt() : 0;
        }
        return c;
    }

    private static JsonObject firstImage(JsonObject o) {
        JsonElement e = o.get("image");
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().isEmpty()) {
            return null;
        }
        JsonElement first = e.getAsJsonArray().get(0);
        return first.isJsonObject() ? first.getAsJsonObject() : null;
    }

    private static String actorName(JsonObject a) {
        if (a == null) {
            return "?";
        }
        String nick = str(a, "nick");
        return nick == null || nick.isEmpty() ? "?" : nick;
    }

    private static String nz(String a, String b) {
        return a == null || a.isEmpty() ? (b == null ? "" : b) : a;
    }

    private static String iso(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }

    private static String str(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static boolean bool(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonPrimitive() && o.get(k).getAsBoolean();
    }

    public static FeedApiException unwrap(Throwable ex) {
        while (ex.getCause() != null && !(ex instanceof FeedApiException)) {
            ex = ex.getCause();
        }
        return ex instanceof FeedApiException fae ? fae : new FeedApiException(ex);
    }
}
