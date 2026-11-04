package com.feed.client;

import com.feed.Feed;
import com.feed.client.screen.RetroUi;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/** Toasts for new items; unread badge until markRead(postId). */
@EventBusSubscriber(modid = Feed.MOD_ID, value = Dist.CLIENT)
public final class NotificationPoller {
    private static final int TOAST_H = 28;
    private static final int TOASTED_CAP = 300;
    private static final int UNREAD_CAP = 100;
    private static final Gson GSON = new GsonBuilder().create();

    private static long lastPoll = 0L;
    private static String fetchCursor = null;
    private static boolean polling = false;
    private static boolean loaded = false;
    private static boolean bootstrapping = false;
    private static boolean shutdownHookRegistered = false;

    private static final List<FeedApiClient.Notif> unread = new CopyOnWriteArrayList<>();
    // Дедуп по id УВЕДОМЛЕНИЯ (не поста): показанное один раз не воскресает после рестарта.
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
        saveSync();
        toasts.clear();
        // Keep unread in memory after saving: the shutdown hook may save again.
        // A subsequent login reloads the correct owner's inbox.
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        if (!loaded) {
            load();
        }
        long now = System.currentTimeMillis();
        if (polling || now - lastPoll < FeedConfig.notificationIntervalSeconds() * 1000L) return;
        lastPoll = now;
        polling = true;
        String uuid = mc.getUser().getProfileId().toString();
        try {
            FeedApiClient.INSTANCE.fetchNotifications(uuid, fetchCursor).whenComplete((res, err) -> {
                polling = false;
                if (err != null || res == null) return;
                mc.execute(() -> {
                    if (mc.player != null && mc.level != null
                            && uuid.equals(mc.getUser().getProfileId().toString())) applyFetch(res);
                });
            });
        } catch (Exception e) {
            // синхронный сбой не должен навсегда заклинить поллер
            polling = false;
            Feed.LOG.debug("notification poll failed", e);
        }
    }

    private static void applyFetch(FeedApiClient.NotificationsResult res) {
        if (bootstrapping) {
            // Preserve existing behavior for old history, but "0" establishes an empty
            // baseline so the very first future notification is not swallowed.
            fetchCursor = NotificationOrder.initialCursor(res.latest());
            bootstrapping = false;
            saveSync();
            return;
        }
        boolean changed = false;
        boolean hasNew = false;
        // The service returns newest first. Process oldest first so advancing the
        // cursor never causes the rest of the same batch to be skipped.
        List<FeedApiClient.Notif> batch = new ArrayList<>(res.notifications() == null ? List.of() : res.notifications());
        batch.removeIf(n -> n == null || n.id() == null || n.id().isEmpty());
        batch.sort((a, b) -> NotificationOrder.compare(a.id(), b.id()));
        for (FeedApiClient.Notif n : batch) {
            if (n == null || n.id() == null || n.id().isEmpty()) continue;
            if (toastedIds.contains(n.id())) continue;
            if (fetchCursor != null && !idGreater(n.id(), fetchCursor)) continue;
            if (!hasUnreadId(n.id())) {
                unread.add(n);
                while (unread.size() > UNREAD_CAP) unread.remove(0);
                changed = true;
                hasNew = true;
                if (toastedIds.add(n.id())) {
                    trimToasted();
                    if (FeedConfig.toastNotificationsEnabled()) {
                        toasts.add(new ToastEntry(n, System.currentTimeMillis()));
                    }
                    if (FeedConfig.chatNotificationsEnabled()) {
                        Minecraft mc = Minecraft.getInstance();
                        if (mc.player != null) {
                            mc.player.displayClientMessage(chatNotification(n), false);
                        }
                    }
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
        if (hasNew && FeedConfig.notificationSoundEnabled()) {
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(
                    SoundEvents.NOTE_BLOCK_PLING.value(), 1.0F, FeedConfig.notificationSoundVolume() / 100.0F));
        }
        if (changed) {
            saveSync();
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

    private static void ensureShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(NotificationPoller::saveSync, "feed-notif-save"));
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post event) {
        toasts.removeIf(t -> System.currentTimeMillis() - t.shownAt > FeedConfig.toastDurationSeconds() * 1000L);
        if (!FeedConfig.toastNotificationsEnabled()) {
            toasts.clear();
            return;
        }
        if (toasts.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        GuiGraphics g = event.getGuiGraphics();
        int sw = g.guiWidth();
        int y = Math.min(FeedConfig.toastOffsetY(), Math.max(0, g.guiHeight() - TOAST_H));
        int shown = 0;
        // Clear previous hitboxes, including toasts hidden by the limit or screen size.
        for (ToastEntry t : toasts) t.h = 0;
        for (ToastEntry t : toasts) {
            if (shown++ >= FeedConfig.maxVisibleToasts() || y + TOAST_H > g.guiHeight()) break;
            String thread = FeedUtil.clip(font, RetroUi.flatten(formatReply(FeedConfig.toastTitleTemplate(), t.notif)), 120);
            String body = FeedUtil.clip(font, RetroUi.flatten(formatReply(FeedConfig.toastBodyTemplate(), t.notif)), 120);
            int w = Math.max(font.width(thread), font.width(body)) + 12;
            int x = FeedConfig.toastOnLeft() ? FeedConfig.toastOffsetX() : sw - w - FeedConfig.toastOffsetX();
            x = Math.max(0, Math.min(x, sw - w));
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
        if (!FeedConfig.toastNotificationsEnabled() || event.getAction() != GLFW.GLFW_PRESS || event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        double mx = guiMouseX(mc);
        double my = guiMouseY(mc);
        int checked = 0;
        for (ToastEntry t : toasts) {
            if (checked++ >= FeedConfig.maxVisibleToasts()) break;
            if (System.currentTimeMillis() - t.shownAt > FeedConfig.toastDurationSeconds() * 1000L) continue;
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

    /** User opened the replies inbox — hide current items, but do NOT mute future ones. */
    public static void markAllSeen() {
        for (FeedApiClient.Notif n : unread) {
            if (n.id() != null && !n.id().isEmpty()) {
                toastedIds.add(n.id());
            }
        }
        trimToasted();
        unread.clear();
        toasts.clear();
        saveSync();
    }

    /** Only when the user opens that thread. Clears current items for the post; future ones still arrive. */
    public static void markRead(String postId) {
        if (postId == null) return;
        for (FeedApiClient.Notif n : unread) {
            if (postId.equals(n.postId()) && n.id() != null) {
                toastedIds.add(n.id());
            }
        }
        trimToasted();
        unread.removeIf(n -> postId.equals(n.postId()));
        toasts.removeIf(t -> postId.equals(t.notif.postId()));
        saveSync();
    }

    private static boolean idGreater(String a, String b) {
        return NotificationOrder.compare(a, b) > 0;
    }

    private static Component chatNotification(FeedApiClient.Notif n) {
        MutableComponent result = Component.literal(formatReply(FeedConfig.chatTemplate(), n))
                .append("\n").append(Component.translatable("feed.chat.notification.post", safe(n.postText())));
        if (FeedChatActions.validPostId(n.postId())) {
            String prefix = FeedChatActions.COMMAND;
            result.append(" ").append(Component.translatable("feed.chat.action.open")
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, prefix + " open " + n.postId()))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("feed.chat.action.open.hover")))));
            result.append(" ").append(Component.translatable("feed.chat.action.reply")
                    .withStyle(style -> style.withColor(ChatFormatting.GREEN).withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, prefix + " reply " + n.postId() + " "))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("feed.chat.action.reply.hover")))));
        }
        return result;
    }

    private static String formatReply(String template, FeedApiClient.Notif n) {
        return MessageTemplate.render(template, java.util.Map.of(
                "author", safe(n.author()), "message", safe(n.text()), "post", safe(n.postText())));
    }

    private static String safe(String value) {
        return value == null || value.isEmpty() ? "?" : value.replace('\n', ' ').replace('\r', ' ');
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

    private static Path file() {
        String owner = Minecraft.getInstance().getUser().getProfileId().toString();
        return Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("feed-notifications-" + owner + ".json");
    }

    /** Pre-account-scoped notification file. Kept intact during migration. */
    private static Path oldJsonFile() {
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
        ensureShutdownHook();
        unread.clear();
        toastedIds.clear();
        fetchCursor = null;
        bootstrapping = false;
        try {
            Path path = file();
            if (!Files.isRegularFile(path) && Files.isRegularFile(oldJsonFile())) path = oldJsonFile();
            if (Files.isRegularFile(path)) {
                JsonObject o = GSON.fromJson(Files.readString(path), JsonObject.class);
                if (o != null) {
                    String owner = FeedUtil.jstr(o, "owner");
                    String currentOwner = Minecraft.getInstance().getUser().getProfileId().toString();
                    if (owner != null && !owner.equalsIgnoreCase(currentOwner)) {
                        bootstrapping = true;
                        return;
                    }
                    if (o.has("cursor") && !o.get("cursor").isJsonNull()) {
                        fetchCursor = o.get("cursor").getAsString();
                    }
                    // Old-format unread values are intentionally not restored.
                    if (owner != null && o.has("inboxVersion") && o.get("inboxVersion").getAsInt() == 2
                            && o.has("unread") && o.get("unread").isJsonArray()) {
                        for (JsonElement el : o.getAsJsonArray("unread")) {
                            try {
                                FeedApiClient.Notif n = GSON.fromJson(el, FeedApiClient.Notif.class);
                                if (n != null && n.id() != null && !n.id().isEmpty() && !hasUnreadId(n.id())) unread.add(n);
                            } catch (Exception ignored) { }
                        }
                        while (unread.size() > UNREAD_CAP) unread.remove(0);
                    }
                    if (o.has("toasted") && o.get("toasted").isJsonArray()) {
                        for (JsonElement el : o.getAsJsonArray("toasted")) {
                            toastedIds.add(el.getAsString());
                        }
                    }
                    // ключ "readPosts" из старых билдов сознательно игнорируется (мьютил треды навсегда)
                    // one-time migration from older builds that only persisted unread[]
                    if (!o.has("inboxVersion") && o.has("unread") && o.get("unread").isJsonArray()) {
                        boolean migrated = false;
                        for (JsonElement el : o.getAsJsonArray("unread")) {
                            if (!el.isJsonObject()) continue;
                            String id = FeedUtil.jstr(el.getAsJsonObject(), "id");
                            if (id != null && !id.isEmpty()) {
                                toastedIds.add(id);
                                migrated = true;
                            }
                        }
                        if (migrated) {
                            trimToasted();
                            saveSync();
                        }
                    }
                }
                bootstrapping = fetchCursor == null || fetchCursor.isEmpty();
                if (!path.equals(file())) saveSync(); // Copy legacy state; never delete the old file.
                return;
            }
            if (Files.isRegularFile(legacyFile())) {
                String s = Files.readString(legacyFile()).trim();
                if (!s.isEmpty()) {
                    fetchCursor = s.lines().map(String::trim).filter(l -> !l.isEmpty()).findFirst().orElse(null);
                }
                bootstrapping = fetchCursor == null || fetchCursor.isEmpty();
                save();
            }
        } catch (Exception e) {
            Feed.LOG.debug("notification load failed", e);
        }
        if (fetchCursor == null || fetchCursor.isEmpty()) {
            bootstrapping = true;
        }
    }

    private static void save() {
        CompletableFuture.runAsync(NotificationPoller::saveSync);
    }

    private static synchronized void saveSync() {
        try {
            Files.createDirectories(file().getParent());
            JsonObject o = new JsonObject();
            o.addProperty("cursor", fetchCursor);
            o.addProperty("inboxVersion", 2);
            o.addProperty("owner", Minecraft.getInstance().getUser().getProfileId().toString());
            o.add("unread", GSON.toJsonTree(new ArrayList<>(unread)));
            JsonArray toasted = new JsonArray();
            for (String id : toastedIds) toasted.add(id);
            o.add("toasted", toasted);
            Path target = file();
            Path temp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.writeString(temp, GSON.toJson(o));
            try {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            Feed.LOG.debug("notification save failed", e);
        }
    }
}