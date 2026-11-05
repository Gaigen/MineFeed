package com.feed.client;

import com.feed.Feed;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pictures of posts (Discord CDN) and local screenshots as GPU textures, plus the JPEG encoder for uploads.
 * Pure ImageIO + manual scaling: no AWT toolkit/Graphics2D (unsafe next to GLFW on macOS).
 */
public final class PostImages {

    /** Ready texture and its size in texture pixels. */
    public record Tex(ResourceLocation location, int w, int h) {}

    public static final int MAX_UPLOAD_BYTES = 1_400_000;
    private static final int FEED_SIDE = 1024;
    private static final int THUMB_SIDE = 256;
    private static final int MAX_DOWNLOAD = 10_000_000;
    private static final long FAIL_BACKOFF_MS = 120_000;
    private static final int LRU_CAP = 24;
    private static final AtomicInteger SEQ = new AtomicInteger();

    private static final Map<String, Tex> READY = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Tex> eldest) {
            if (size() <= LRU_CAP) {
                return false;
            }
            ResourceLocation rl = eldest.getValue().location();
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().getTextureManager().release(rl));
            return true;
        }
    };
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();

    static {
        ImageIO.setUseCache(false);
    }

    private PostImages() {}

    /** Only Discord's CDN: the server never hands out other hosts. */
    public static boolean allowed(String url) {
        return url != null && (url.startsWith("https://cdn.discordapp.com/") || url.startsWith("https://media.discordapp.net/"));
    }

    /** Signed links change every day; the file is the same, so the key drops the query. */
    private static String keyOf(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }

    /** Texture of a post picture, or null while loading. */
    public static Tex get(String url) {
        if (!allowed(url)) {
            return null;
        }
        return lookup("url:" + keyOf(url), () -> download(url), FEED_SIDE);
    }

    /** True when the picture couldn't be loaded recently (show a placeholder). */
    public static boolean failed(String url) {
        if (url == null) {
            return true;
        }
        Long at = FAILED.get(url.startsWith("file:") ? url : "url:" + keyOf(url));
        return at != null && System.currentTimeMillis() - at < FAIL_BACKOFF_MS;
    }

    /** Small preview of a local screenshot. */
    public static Tex thumb(Path file) {
        return lookup("file:" + file.toAbsolutePath(), () -> Files.readAllBytes(file), THUMB_SIDE);
    }

    public static boolean thumbFailed(Path file) {
        return failed("file:" + file.toAbsolutePath());
    }

    private interface Bytes {
        byte[] read() throws Exception;
    }

    private static Tex lookup(String key, Bytes source, int side) {
        synchronized (READY) {
            Tex t = READY.get(key);
            if (t != null) {
                return t;
            }
        }
        Long at = FAILED.get(key);
        if (at != null && System.currentTimeMillis() - at < FAIL_BACKOFF_MS) {
            return null;
        }
        if (LOADING.add(key)) {
            CompletableFuture.runAsync(() -> load(key, source, side));
        }
        return null;
    }

    private static byte[] download(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<byte[]> res = FeedHttp.CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() != 200 || res.body().length > MAX_DOWNLOAD) {
            throw new IOException("HTTP " + res.statusCode());
        }
        return res.body();
    }

    private static void load(String key, Bytes source, int side) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(source.read()));
            if (src == null) {
                throw new IOException("unsupported format");
            }
            int[] px = scaled(src, side);
            int w = px[px.length - 2];
            int h = px[px.length - 1];
            NativeImage img = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = px[y * w + x];
                    // NativeImage is ABGR
                    int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
                    img.setPixelRGBA(x, y, abgr);
                }
            }
            Minecraft.getInstance().execute(() -> {
                DynamicTexture tex = new DynamicTexture(img);
                tex.setFilter(true, false);
                ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("feed", "post_image/" + SEQ.incrementAndGet());
                Minecraft.getInstance().getTextureManager().register(rl, tex);
                synchronized (READY) {
                    READY.put(key, new Tex(rl, w, h));
                }
                FAILED.remove(key);
                LOADING.remove(key);
            });
        } catch (Throwable e) {
            Feed.LOG.debug("image load failed for {}", key, e);
            FAILED.put(key, System.currentTimeMillis());
            LOADING.remove(key);
        }
    }

    /**
     * ARGB pixels scaled so the longest side is at most {@code side} (box filter), with w and h appended.
     */
    private static int[] scaled(BufferedImage src, int side) {
        int sw = src.getWidth();
        int sh = src.getHeight();
        int[] in = src.getRGB(0, 0, sw, sh, null, 0, sw);
        double k = Math.min(1.0, side / (double) Math.max(sw, sh));
        int w = Math.max(1, (int) Math.round(sw * k));
        int h = Math.max(1, (int) Math.round(sh * k));
        int[] out = new int[w * h + 2];
        for (int y = 0; y < h; y++) {
            int y0 = y * sh / h;
            int y1 = Math.max(y0 + 1, (y + 1) * sh / h);
            for (int x = 0; x < w; x++) {
                int x0 = x * sw / w;
                int x1 = Math.max(x0 + 1, (x + 1) * sw / w);
                long a = 0, r = 0, g = 0, b = 0;
                int n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    int row = yy * sw;
                    for (int xx = x0; xx < x1; xx++) {
                        int c = in[row + xx];
                        a += c >>> 24;
                        r += (c >> 16) & 0xFF;
                        g += (c >> 8) & 0xFF;
                        b += c & 0xFF;
                        n++;
                    }
                }
                out[y * w + x] = (int) (a / n) << 24 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        }
        out[w * h] = w;
        out[w * h + 1] = h;
        return out;
    }

    /** Screenshot -> base64 JPEG small enough for the server (tries smaller sizes if needed). Blocking. */
    public static String encodeForUpload(Path file) throws IOException {
        BufferedImage src = ImageIO.read(file.toFile());
        if (src == null) {
            throw new IOException("unsupported format");
        }
        int[][] tries = {{1600, 85}, {1280, 80}, {1024, 75}, {800, 70}};
        for (int[] t : tries) {
            int[] px = scaled(src, t[0]);
            int w = px[px.length - 2];
            int h = px[px.length - 1];
            BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            rgb.setRGB(0, 0, w, h, px, 0, w);
            byte[] jpg = jpeg(rgb, t[1] / 100f);
            if (jpg.length <= MAX_UPLOAD_BYTES) {
                return Base64.getEncoder().encodeToString(jpg);
            }
        }
        throw new IOException("too large");
    }

    private static byte[] jpeg(BufferedImage img, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), p);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
