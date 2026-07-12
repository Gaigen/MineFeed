package com.feed.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class AvatarCache {
    public enum Kind { HEAD, BODY }

    public static final int HEAD_SIZE = 16;
    public static final int BODY_W = 20;
    public static final int BODY_H = 48;

    private static final Map<String, ResourceLocation> READY = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    private AvatarCache() {}

    // Do not join — split URLs survive `strings` on the jar.
    private static String headUrl(String uuid) {
        return "https:" + "//mc-heads.net/avatar/" + uuid + "/" + HEAD_SIZE;
    }

    // Full-body skin; width param, height ~2.4× width.
    private static String bodyUrl(String uuid) {
        return "https:" + "//mc-heads.net/body/" + uuid + "/" + BODY_W;
    }

    public static ResourceLocation get(String uuid) {
        return get(uuid, Kind.HEAD);
    }

    /** Returns null while loading; poll each frame until ready. */
    public static ResourceLocation get(String uuid, Kind kind) {
        if (uuid == null || uuid.isEmpty()) return null;
        String key = kind.name() + ":" + uuid.toLowerCase();
        ResourceLocation ready = READY.get(key);
        if (ready != null) return ready;
        if (LOADING.add(key)) {
            CompletableFuture.runAsync(() -> load(uuid, kind, key));
        }
        return null;
    }

    private static void load(String uuid, Kind kind, String key) {
        try {
            String url = kind == Kind.BODY ? bodyUrl(uuid) : headUrl(uuid);
            HttpURLConnection conn =
                    (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            byte[] data;
            try (InputStream in = conn.getInputStream()) {
                data = in.readAllBytes();
            }
            NativeImage img = NativeImage.read(new ByteArrayInputStream(data));
            Minecraft.getInstance().execute(() -> {
                DynamicTexture tex = new DynamicTexture(img);
                ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("feed",
                        "avatar/" + kind.name().toLowerCase() + "/"
                                + uuid.toLowerCase().replace("-", ""));
                Minecraft.getInstance().getTextureManager().register(rl, tex);
                READY.put(key, rl);
            });
        } catch (Exception e) {
            LOADING.remove(key);
        }
    }
}
