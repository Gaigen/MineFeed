package com.feed.client;

import com.feed.Feed;
import com.feed.client.screen.RetroUi;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/** Toasts for new items; unread badge until markRead(postId). */
@EventBusSubscriber(modid = Feed.MOD_ID, value = Dist.CLIENT)
public final class NotificationPoller {
    private static final long INTERVAL_MS = 20_000L;
    private static final long TOAST_MS = 8_000L;
    private static final int MAX_TOASTS = 3;
    private static final int TOAST_H = 28;
    private static final int TOASTED_CAP = 300;
    private static final Gson GSON = new GsonBuilder().create();

    private static long lastPoll = 0L;
    private static String fetchCursor = null;
    private static boolean polling = false;
    private static boolean loaded = false;

    private static final List<FeedApiClient.Notif> unread = new CopyOnWriteArrayList<>();
    private static final LinkedHashSet<String> toastedIds = new LinkedHashSet<>();
    private static final List<ToastEntry> toasts = new CopyOnWriteArrayList<>();

    private static final class ToastEntry {
        final FeedApiClient.Notif notif;
        final long shownAt;
        int x, y, w, h;

        ToastEntry(FeedApiClient.Notif notif, long shownAt) {
            this.notif = notif;
            this.shownAt = shownAt;
        }
    }

    private NotificationPoller() {}

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        load();
        toasts.clear();
        lastPoll = 0L;
        polling = false;
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        save();
        toasts.clear();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!loaded) {
            load();
        }
        long now = System.currentTimeMillis();
        if (polling || now - lastPoll < INTERVAL_MS) return;
        lastPoll = now;
        polling = true;
        String uuid = mc.getUser().getProfileId().toString();
        FeedApiClient.INSTANCE.fetchNotifications(uuid, fetchCursor).whenComplete((res, err) -> {
            polling = false;
            if (err != null || res == null) return;
            mc.execute(() -> applyFetch(res));
        });
    }

    private static void applyFetch(FeedApiClient.NotificationsResult res) {
        boolean changed = false;
        for (FeedApiClient.Notif n : res.notifications()) {
            if (n == null || n.id() == null || n.id().isEmpty()) continue;
            if (fetchCursor != null && !idGreater(n.id(), fetchCursor)) continue;
            if (!hasUnreadId(n.id())) {
                unread.add(n);
                changed = true;
                if (toastedIds.add(n.id())) {
                    trimToasted();
                    toasts.add(new ToastEntry(n, System.currentTimeMillis()));
                }
            }
            if (fetchCursor == null || idGreater(n.id(), fetchCursor)) {
                fetchCursor = n.id();
                changed = true;
            }
        }
        if (res.latest() != null && (fetchCursor == null || idGreater(res.latest(), fetchCursor))) {
            fetchCursor = res.latest();
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    private static boolean hasUnreadId(String id) {
        for (FeedApiClient.Notif n : unread) {
            if (id.equals(n.id())) return true;
        }
        return false;
    }

    private static void trimToasted() {
        while (toastedIds.size() > TOASTED_CAP) {
            var it = toastedIds.iterator();
            it.next();
            it.remove();
        }
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        toasts.removeIf(t -> System.currentTimeMillis() - t.shownAt > TOAST_MS);
        if (toasts.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        GuiGraphics g = event.getGuiGraphics();
        int sw = g.guiWidth();
        int y = 8;
        int shown = 0;
        for (ToastEntry t : toasts) {
            if (shown++ >= MAX_TOASTS) break;
            String thread = "\u2192 " + clip(font, quote(RetroUi.flatten(t.notif.postText())), 110);
            String body = clip(font, RetroUi.flatten(t.notif.author() + ": " + t.notif.text()), 120);
            int w = Math.max(font.width(thread), font.width(body)) + 12;
            int x = sw - w - 8;
            boolean hover = isHover(mc, x, y, w, TOAST_H);
            g.fill(x + 2, y + 2, x + w + 2, y + TOAST_H + 2, RetroUi.SHADOW);
            g.fill(x, y, x + w, y + TOAST_H, hover ? RetroUi.HOVER_BG : RetroUi.BG);
            g.fill(x, y, x + w, y + 1, hover ? RetroUi.ACCENT : RetroUi.GRAY);
            g.fill(x, y + TOAST_H - 1, x + w, y + TOAST_H, RetroUi.GRAY);
            g.fill(x, y, x + 1, y + TOAST_H, RetroUi.GRAY);
            g.fill(x + w - 1, y, x + w, y + TOAST_H, RetroUi.GRAY);
            g.drawString(font, thread, x + 6, y + 4, RetroUi.ACCENT, false);
            g.drawString(font, body, x + 6, y + 16, RetroUi.FG, false);
            t.x = x;
            t.y = y;
            t.w = w;
            t.h = TOAST_H;
            y += TOAST_H + 4;
        }
    }

    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != GLFW.GLFW_PRESS || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        double mx = guiMouseX(mc);
        double my = guiMouseY(mc);
        for (ToastEntry t : toasts) {
            if (t.h > 0 && mx >= t.x && mx < t.x + t.w && my >= t.y && my < t.y + t.h) {
                event.setCanceled(true);
                FeedApiClient.Notif n = t.notif;
                toasts.remove(t);
                mc.execute(() -> PostOpener.open(mc, null, n.postId()));
                return;
            }
        }
    }

    public static boolean hasUnread() {
        return !unread.isEmpty();
    }

    public static int unreadCount() {
        return unread.size();
    }

    public static List<FeedApiClient.Notif> unreadList() {
        return new ArrayList<>(unread);
    }

    /** Only when the user opens that thread. */
    public static void markRead(String postId) {
        if (postId == null) return;
        boolean removed = unread.removeIf(n -> postId.equals(n.postId()));
        toasts.removeIf(t -> postId.equals(t.notif.postId()));
        if (removed) {
            save();
        }
    }

    private static boolean idGreater(String a, String b) {
        if (a == null || b == null) return a != null;
        if (a.length() != b.length()) return a.length() > b.length();
        return a.compareTo(b) > 0;
    }

    private static String quote(String s) {
        if (s == null || s.isEmpty()) return "?";
        return "\u00ab" + s + "\u00bb";
    }

    private static boolean isHover(Minecraft mc, int x, int y, int w, int h) {
        double mx = guiMouseX(mc);
        double my = guiMouseY(mc);
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static double guiMouseX(Minecraft mc) {
        return mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth()
                / (double) mc.getWindow().getScreenWidth();
    }

    private static double guiMouseY(Minecraft mc) {
        return mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight()
                / (double) mc.getWindow().getScreenHeight();
    }

    private static String clip(Font font, String s, int maxWidth) {
        if (s == null) return "";
        if (font.width(s) <= maxWidth) return s;
        return font.plainSubstrByWidth(s, maxWidth - font.width("...")) + "...";
    }

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("feed-notifications.json");
    }

    /** Legacy single-line cursor file from older builds. */
    private static Path legacyFile() {
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("feed-notifications.txt");
    }

    private static void load() {
        loaded = true;
        unread.clear();
        toastedIds.clear();
        fetchCursor = null;
        try {
            Path path = file();
            if (Files.isRegularFile(path)) {
                JsonObject o = GSON.fromJson(Files.readString(path), JsonObject.class);
                if (o != null) {
                    if (o.has("cursor") && !o.get("cursor").isJsonNull()) {
                        fetchCursor = o.get("cursor").getAsString();
                    }
                    if (o.has("unread") && o.get("unread").isJsonArray()) {
                        for (JsonElement el : o.getAsJsonArray("unread")) {
                            FeedApiClient.Notif n = parseNotif(el.getAsJsonObject());
                            if (n != null) unread.add(n);
                        }
                    }
                    if (o.has("toasted") && o.get("toasted").isJsonArray()) {
                        for (JsonElement el : o.getAsJsonArray("toasted")) {
                            toastedIds.add(el.getAsString());
                        }
                    }
                }
                return;
            }
            if (Files.isRegularFile(legacyFile())) {
                String s = Files.readString(legacyFile()).trim();
                if (!s.isEmpty()) {
                    fetchCursor = s.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse(null);
                }
                save();
            }
        } catch (Exception ignored) {
        }
    }

    private static FeedApiClient.Notif parseNotif(JsonObject o) {
        if (o == null) return null;
        String id = jstr(o, "id");
        if (id == null || id.isEmpty()) return null;
        return new FeedApiClient.Notif(
                id,
                jstr(o, "postId"),
                jstr(o, "postText"),
                jstr(o, "author"),
                jstr(o, "text"),
                jstr(o, "createdAt"));
    }

    private static String jstr(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            JsonObject o = new JsonObject();
            o.addProperty("cursor", fetchCursor);
            JsonArray arr = new JsonArray();
            for (FeedApiClient.Notif n : unread) {
                JsonObject e = new JsonObject();
                e.addProperty("id", n.id());
                e.addProperty("postId", n.postId());
                e.addProperty("postText", n.postText());
                e.addProperty("author", n.author());
                e.addProperty("text", n.text());
                e.addProperty("createdAt", n.createdAt());
                arr.add(e);
            }
            o.add("unread", arr);
            JsonArray toasted = new JsonArray();
            for (String id : toastedIds) toasted.add(id);
            o.add("toasted", toasted);
            Files.writeString(file(), GSON.toJson(o));
        } catch (Exception ignored) {
        }
    }
}
