package com.feed.client.screen;

import com.feed.client.CommunityApi;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedTime;
import com.feed.client.FeedUtil;
import com.feed.client.NotificationPoller;
import com.feed.client.PostOpener;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Notifications page: replies, mentions, reactions and community news; unread ones have a yellow frame. */
public class NotificationsScreen extends ShellScreen {

    private static final int TABS_Y = 22;
    private static final int LIST_Y = 40;
    private static final int GAP = 6;
    private static final int BTN_H = 13;
    private static final long REFRESH_MS = 20_000L;

    private enum Tab {
        ALL("feed.notifs.tab.all"), REPLY("feed.notifs.tab.replies"), MENTION("feed.notifs.tab.mentions"),
        COMM("feed.notifs.tab.communities");

        final String key;

        Tab(String key) {
            this.key = key;
        }

        boolean accepts(FeedApiClient.Notif n) {
            String k = n.kind() == null ? "" : n.kind();
            return switch (this) {
                case ALL -> true;
                case REPLY -> k.equals("comment");
                case MENTION -> k.equals("mention");
                case COMM -> k.equals("request") || k.equals("accepted") || k.startsWith("app_");
            };
        }
    }

    private static Tab tab = Tab.ALL;
    private List<FeedApiClient.Notif> items = List.of();
    private final Map<String, String> decided = new HashMap<>();
    private boolean loading;
    private Component error;
    private long lastFetch;
    private int lastUnread = -1;
    private int scroll;
    private int contentHeight;
    private RetroButton readAll;

    public NotificationsScreen(FeedScreen home) {
        super(Component.translatable("feed.screen.notifications.title"), home);
    }

    @Override
    protected String navId() {
        return "notifs";
    }

    @Override
    protected void init() {
        Component label = Component.translatable("feed.screen.notifications.clear");
        int bw = this.font.width(label) + 12;
        readAll = addRenderableWidget(new RetroButton(contentLeft() + contentWidth() - bw, 5, bw, BTN_H, label, b -> {
            NotificationPoller.markAllSeen();
        }));
        if (items.isEmpty() && !loading) {
            fetch();
        }
    }

    private void fetch() {
        loading = true;
        lastFetch = System.currentTimeMillis();
        lastUnread = NotificationPoller.unreadCount();
        FeedApiClient.INSTANCE.fetchNotifications(null, null).whenComplete((res, ex) -> this.minecraft.execute(() -> {
            loading = false;
            if (ex != null) {
                error = FeedApiClient.unwrap(ex).userMessage();
                return;
            }
            error = null;
            items = res.notifications();
        }));
    }

    @Override
    public void tick() {
        super.tick();
        readAll.active = NotificationPoller.unreadCount() > 0;
        long now = System.currentTimeMillis();
        if (!loading && (now - lastFetch > REFRESH_MS || NotificationPoller.unreadCount() > lastUnread)) {
            fetch();
        }
        lastUnread = Math.min(lastUnread, NotificationPoller.unreadCount());
    }

    private List<FeedApiClient.Notif> visible() {
        List<FeedApiClient.Notif> out = new ArrayList<>();
        for (FeedApiClient.Notif n : items) {
            if (tab.accepts(n) && (!com.feed.client.HiddenPlayers.isHidden(n.actorId()) || isRequest(n))) {
                out.add(n);
            }
        }
        return out;
    }

    private static boolean isRequest(FeedApiClient.Notif n) {
        return "request".equals(n.kind());
    }

    private boolean unread(FeedApiClient.Notif n) {
        return NotificationPoller.hasUnreadId(n.id());
    }

    /** "@tag" (accent) before the line, or null for system messages. */
    private String actor(FeedApiClient.Notif n) {
        String k = n.kind() == null ? "" : n.kind();
        if (k.startsWith("app_")) {
            return null;
        }
        if (n.actorTag() != null) {
            return "@" + n.actorTag();
        }
        return n.author() == null || n.author().isEmpty() ? null : n.author();
    }

    private String line(FeedApiClient.Notif n) {
        String k = n.kind() == null ? "" : n.kind();
        String community = n.communityName() != null ? n.communityName() : "?";
        return switch (k) {
            case "comment" -> tr("feed.notifs.comment");
            case "mention" -> tr("feed.notifs.mention");
            case "request" -> tr("feed.notifs.request", community);
            case "accepted" -> tr("feed.notifs.accepted", community);
            default -> RetroUi.flatten(n.text());
        };
    }

    private String quote(FeedApiClient.Notif n) {
        String k = n.kind() == null ? "" : n.kind();
        String q = switch (k) {
            case "comment", "mention" -> n.text();
            case "react" -> n.postText();
            default -> null;
        };
        q = RetroUi.flatten(q);
        return q.isEmpty() ? null : "\u00ab" + q + "\u00bb";
    }

    private int heightOf(FeedApiClient.Notif n) {
        return isRequest(n) ? 40 : quote(n) != null ? 30 : 22;
    }

    // ---------- rendering ----------

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int x = contentLeft();
        int w = contentWidth();
        int unread = NotificationPoller.unreadCount();
        drawTitle(g, this.title.getString(), unread > 0 ? tr("feed.notifs.new", unread) : null, x, TITLE_Y, readAll.getX() - 6);

        int tx = x;
        for (Tab t : Tab.values()) {
            String label = tr(t.key);
            int cw = RetroUi.chipWidth(this.font, label);
            boolean hover = t != tab && mouseX >= tx && mouseX < tx + cw && mouseY >= TABS_Y && mouseY < TABS_Y + RetroUi.CHIP_H;
            RetroUi.drawChip(g, this.font, label, tx, TABS_Y, hover ? RetroUi.FG : RetroUi.ACCENT, t == tab);
            tx += cw + 3;
        }

        int top = LIST_Y;
        int bottom = this.height - 4;
        boolean inList = mouseY >= top && mouseY < bottom;
        g.enableScissor(sidebarW(), top, this.width, bottom);
        int y = top - scroll;
        List<FeedApiClient.Notif> list = visible();
        for (FeedApiClient.Notif n : list) {
            int h = heightOf(n);
            if (y + h + RetroUi.SHADOW_OFFSET >= top && y <= bottom) {
                drawItem(g, n, x, y, w, h, inList ? mouseX : -1, inList ? mouseY : -1);
            }
            y += h + GAP;
        }
        contentHeight = y + scroll - top;
        if (list.isEmpty()) {
            String msg = loading ? tr("feed.screen.loading") + (RetroUi.blinkFast() ? "_" : "")
                    : error != null ? error.getString() : tr("feed.notifs.empty");
            g.text(this.font, msg, x + 6, top + 6, error != null ? RetroUi.ERROR : RetroUi.GRAY, false);
        }
        g.disableScissor();
        drawScrollbar(g, top, bottom, scroll, contentHeight);
    }

    private void drawItem(GuiGraphicsExtractor g, FeedApiClient.Notif n, int x, int y, int w, int h, int mouseX, int mouseY) {
        boolean unread = unread(n);
        boolean clickable = !isRequest(n) || decided.containsKey(n.id());
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        RetroUi.drawBox(g, x, y, w, h, hover && clickable);
        if (unread) {
            g.outline(x, y, w, h, RetroUi.ACCENT);
        }
        String actor = actor(n);
        String key = n.avatar() != null ? n.avatar() : null;
        if (actor == null) {
            g.fill(x + 5, y + 3, x + 21, y + 19, RetroUi.HOVER_BG);
            PixelIcon.draw(g, "crown", x + 10, y + 8, RetroUi.ACCENT);
        } else {
            RetroUi.drawAvatar(g, this.font, key, n.author(), x + 5, y + 3, 16);
        }
        int ly = h == 22 ? y + 7 : y + 4;
        String time = FeedTime.ago(n.createdAt());
        int timeX = x + w - this.font.width(time) - 6;
        g.text(this.font, time, timeX, ly, RetroUi.GRAY, false);
        if (unread) {
            g.fill(timeX - 6, ly + 2, timeX - 3, ly + 5, RetroUi.ACCENT);
        }
        int nx = x + 26;
        int limit = timeX - 10;
        if (actor != null) {
            String a = FeedUtil.clip(this.font, actor, Math.max(10, (limit - nx) / 2));
            g.text(this.font, a, nx, ly, RetroUi.ACCENT, false);
            nx += this.font.width(a) + 4;
        }
        g.text(this.font, FeedUtil.clip(this.font, line(n), Math.max(10, limit - nx)), nx, ly, RetroUi.FG, false);
        String q = quote(n);
        if (q != null) {
            g.text(this.font, FeedUtil.clip(this.font, q, w - 34), x + 26, y + 15, RetroUi.GRAY, false);
        }
        if (isRequest(n)) {
            String done = decided.get(n.id());
            if (done != null) {
                g.text(this.font, done, x + 26, y + 24, RetroUi.GRAY, false);
            } else {
                String yes = tr("feed.notifs.accept");
                String no = tr("feed.notifs.decline");
                int yw = RetroUi.buttonWidth(this.font, yes);
                int nw = RetroUi.buttonWidth(this.font, no);
                int by = y + 21;
                boolean hy = mouseX >= x + 26 && mouseX < x + 26 + yw && mouseY >= by && mouseY < by + BTN_H;
                boolean hn = mouseX >= x + 30 + yw && mouseX < x + 30 + yw + nw && mouseY >= by && mouseY < by + BTN_H;
                RetroUi.drawButton(g, this.font, yes, x + 26, by, yw, BTN_H, hy, true, AccountUi.GREEN);
                RetroUi.drawButton(g, this.font, no, x + 30 + yw, by, nw, BTN_H, hn, true, RetroUi.FG);
            }
        }
    }

    // ---------- input ----------

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x(), mouseY = event.y(); int button = event.button();
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        int x = contentLeft();
        int w = contentWidth();
        if (mouseY >= TABS_Y && mouseY < TABS_Y + RetroUi.CHIP_H) {
            int tx = x;
            for (Tab t : Tab.values()) {
                int cw = RetroUi.chipWidth(this.font, tr(t.key));
                if (mouseX >= tx && mouseX < tx + cw) {
                    tab = t;
                    scroll = 0;
                    return true;
                }
                tx += cw + 3;
            }
            return false;
        }
        if (mouseY < LIST_Y || mouseY >= this.height - 4 || mouseX < x || mouseX >= x + w) {
            return false;
        }
        int y = LIST_Y - scroll;
        for (FeedApiClient.Notif n : visible()) {
            int h = heightOf(n);
            if (mouseY >= y && mouseY < y + h) {
                clickItem(n, x, y, mouseX, mouseY);
                return true;
            }
            y += h + GAP;
        }
        return false;
    }

    private void clickItem(FeedApiClient.Notif n, int x, int y, double mouseX, double mouseY) {
        if (isRequest(n) && !decided.containsKey(n.id())) {
            int yw = RetroUi.buttonWidth(this.font, tr("feed.notifs.accept"));
            int nw = RetroUi.buttonWidth(this.font, tr("feed.notifs.decline"));
            int by = y + 21;
            if (mouseY >= by && mouseY < by + BTN_H && n.communityId() != null && n.actorId() != null) {
                if (mouseX >= x + 26 && mouseX < x + 26 + yw) {
                    decide(n, true);
                } else if (mouseX >= x + 30 + yw && mouseX < x + 30 + yw + nw) {
                    decide(n, false);
                }
            }
            return;
        }
        NotificationPoller.markNotifRead(n.id());
        PostOpener.open(this.minecraft, home(), n, this);
    }

    private void decide(FeedApiClient.Notif n, boolean approve) {
        decided.put(n.id(), "...");
        CommunityApi.decide(n.communityId(), n.actorId(), approve).whenComplete((v, ex) -> this.minecraft.execute(() -> {
            if (ex != null) {
                decided.put(n.id(), FeedApiClient.unwrap(ex).userMessage().getString());
                return;
            }
            decided.put(n.id(), tr(approve ? "feed.notifs.accepted_done" : "feed.notifs.declined_done"));
            NotificationPoller.markNotifRead(n.id());
            home().loadCommunities();
        }));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        int viewport = this.height - 4 - LIST_Y;
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - viewport), scroll - (int) (dy * SCROLL_STEP)));
        return true;
    }
}
