package com.feed.client;

import com.feed.Feed;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class AvatarCache {
    public enum Kind { HEAD, BODY }

    public static final int HEAD_SIZE = 16;
    public static final int BODY_W = 20;
    public static final int BODY_H = 48;

    private static final long FAIL_BACKOFF_MS = 60_000;
    private static final int LRU_CAP = 64;

    private static final Map<String, Entry> READY = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            if (size() <= LRU_CAP) {
                return false;
            }
            Entry e = eldest.getValue();
            Minecraft.getInstance().execute(() -> {
                Minecraft.getInstance().getTextureManager().release(e.location());
                e.texture().close();
            });
            return true;
        }
    };
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private record Entry(ResourceLocation location, DynamicTexture texture) {}

    private AvatarCache() {}

    private static String headUrl(String uuid) {
        return "https:" + "//mc-heads.net/avatar/" + uuid + "/" + HEAD_SIZE;
    }

    private static String bodyUrl(String uuid) {
        return "https:" + "//mc-heads.net/body/" + uuid + "/" + BODY_W;
    }

    public static ResourceLocation get(String uuid) {
        return get(uuid, Kind.HEAD);
    }

    public static ResourceLocation get(String uuid, Kind kind) {
        if (uuid == null || uuid.isEmpty()) return null;
        String key = kind.name() + ":" + uuid.toLowerCase();
        synchronized (READY) {
            Entry entry = READY.get(key);
            if (entry != null) {
                return entry.location();
            }
        }
        Long failedAt = FAILED.get(key);
        if (failedAt != null && System.currentTimeMillis() - failedAt < FAIL_BACKOFF_MS) {
            return null;
        }
        if (LOADING.add(key)) {
            CompletableFuture.runAsync(() -> load(uuid, kind, key));
        }
        return null;
    }

    private static void load(String uuid, Kind kind, String key) {
        try {
            String url = kind == Kind.BODY ? bodyUrl(uuid) : headUrl(uuid);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(4))
                    .GET()
                    .build();
            HttpResponse<byte[]> res = FeedHttp.CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) {
                markFailed(key);
                return;
            }
            NativeImage img = NativeImage.read(new ByteArrayInputStream(res.body()));
            Minecraft.getInstance().execute(() -> {
                DynamicTexture tex = new DynamicTexture(img);
                ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("feed",
                        "avatar/" + kind.name().toLowerCase() + "/"
                                + uuid.toLowerCase().replace("-", ""));
                Minecraft.getInstance().getTextureManager().register(rl, tex);
                synchronized (READY) {
                    READY.put(key, new Entry(rl, tex));
                }
                FAILED.remove(key);
                LOADING.remove(key);
            });
        } catch (Exception e) {
            Feed.LOG.debug("avatar load failed for {}", key, e);
            markFailed(key);
        }
    }

    private static void markFailed(String key) {
        FAILED.put(key, System.currentTimeMillis());
        LOADING.remove(key);
    }
}
