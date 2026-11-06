package com.feed.client.screen;

import com.feed.client.CommunityApi;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedUtil;
import com.feed.client.NotificationPoller;
import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.UpdateChecker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Every main page of MineFeed (feed, post, notifications, profile, themes) lives inside this shell:
 * the sidebar on the left stays put and the page is drawn in a centered column on the right.
 */
public abstract class ShellScreen extends RetroScreen {

    protected static final int TITLE_Y = 8;
    private static final int SB_TOP = 22;
    private static final int SB_ROW = 15;
    private static final int ROW_H = 13;
    protected static final int SCROLL_STEP = 18;

    private final FeedScreen home;
    private int sbScroll;

    /** One clickable line in the sidebar. */
    private record Item(String id, String icon, String label, int color, boolean dim, String badge, int badgeColor,
                        Runnable action) {}

    /** home = the feed screen to go back to; null only for the feed itself. */
    protected ShellScreen(Component title, FeedScreen home) {
        super(title);
        this.home = home;
    }

    protected FeedScreen home() {
        return home != null ? home : (FeedScreen) this;
    }

    /** Sidebar entry to highlight: "feed", "notifs", "mod", "profile", "themes", "c:&lt;community id&gt;" or null. */
    protected abstract String navId();

    // ---------- geometry ----------

    protected int sidebarW() {
        return this.width >= 720 ? 124 : 108;
    }

    @Override
    protected int contentWidth() {
        int avail = this.width - sidebarW();
        return Math.max(120, Math.min(avail - 20, avail >= 600 ? 440 : 384));
    }

    @Override
    protected int contentLeft() {
        int sb = sidebarW();
        return sb + (this.width - sb - contentWidth()) / 2;
    }

    // ---------- navigation ----------

    protected void showHome() {
        if (this.minecraft.screen != home()) {
            this.minecraft.setScreen(home());
        }
    }

    protected String ownId() {
        AccountApi.Account a = AccountManager.INSTANCE.account();
        return a == null ? null : a.id();
    }

    public void openFeed(String space) {
        home().switchSpace(space);
        showHome();
    }

    public void openPlayer(String key) {
        home().openProfile(key);
        showHome();
    }

    /** Feed of the space filtered by #tag (tag without "#"). */
    public void openHashtag(String space, String tag) {
        home().openHashtag(space, tag);
        showHome();
    }

    private <T extends ModalScreen> void modal(T screen) {
        screen.from(this);
        this.minecraft.setScreen(screen);
    }

    private List<Item> topItems() {
        List<Item> items = new ArrayList<>();
        int unread = NotificationPoller.unreadCount();
        items.add(new Item("feed", "home", tr("feed.nav.feed"), 0, false, null, 0, () -> openFeed("global")));
        items.add(new Item("notifs", "bell", tr("feed.nav.notifications"), 0, false,
                unread > 0 ? String.valueOf(Math.min(unread, 99)) : null, RetroUi.ACCENT, () -> {
            if (!(this instanceof NotificationsScreen)) {
                this.minecraft.setScreen(new NotificationsScreen(home()));
            }
        }));
        if (CommunityApi.anyModeration()) {
            CommunityApi.Mine m = CommunityApi.cached();
            int open = m == null ? 0 : m.reportsOpen();
            items.add(new Item("mod", "shield", tr("feed.nav.moderation"), 0, false,
                    open > 0 ? String.valueOf(Math.min(open, 99)) : null, RetroUi.ERROR, () -> {
                if (!(this instanceof ModerationScreen)) {
                    this.minecraft.setScreen(new ModerationScreen(home()));
                }
            }));
        }
        items.add(new Item("profile", "user", tr("feed.nav.profile"), 0, false, null, 0, () -> openPlayer(ownId())));
        items.add(new Item("search", "search", tr("feed.nav.search"), 0, false, null, 0, () -> modal(new SearchScreen(home()))));
        items.add(new Item("themes", "palette", tr("feed.nav.theme"), 0, false, null, 0, () -> {
            if (!(this instanceof ThemeScreen)) {
                this.minecraft.setScreen(new ThemeScreen(home()));
            }
        }));
        items.add(new Item("discord", "discord", tr("feed.nav.discord"), 0, false, null, 0,
                () -> net.minecraft.Util.getPlatform().openUri(UpdateChecker.discordUrl())));
        return items;
    }

    private List<Item> communityItems() {
        List<Item> items = new ArrayList<>();
        CommunityApi.Mine m = CommunityApi.cached();
        if (m != null) {
            for (CommunityApi.Community c : m.communities()) {
                String badge = null;
                int badgeColor = RetroUi.ACCENT;
                if (c.isMod() && c.requests() > 0) {
                    badge = "+" + c.requests();
                    badgeColor = RetroUi.ERROR;
                } else if (c.unread() > 0) {
                    badge = String.valueOf(Math.min(c.unread(), 99));
                }
                String id = c.id();
                items.add(new Item("c:" + id, c.isOwner() ? "crown" : "lock", c.name(), 0, false, badge, badgeColor,
                        () -> openFeed(id)));
            }
            for (CommunityApi.Pending p : m.pending()) {
                items.add(new Item(null, "clock", p.name(), 0, true, null, 0, null));
            }
        }
        items.add(new Item(null, "key", tr("feed.nav.join"), RetroUi.ACCENT, false, null, 0,
                () -> modal(new JoinCodeScreen(home()))));
        CommunityApi.Application app = m == null ? null : m.application();
        boolean owns = m != null && m.communities().stream().anyMatch(CommunityApi.Community::isOwner);
        if (app != null && app.pending()) {
            items.add(new Item(null, "clock", tr("feed.nav.applied"), 0, true, null, 0, () -> modal(new ApplyScreen(home()))));
        } else if (!owns) {
            items.add(new Item(null, "plus", tr("feed.nav.apply"), RetroUi.ACCENT, false, null, 0,
                    () -> modal(new ApplyScreen(home()))));
        }
        return items;
    }

    private int communitiesTop() {
        return SB_TOP + topItems().size() * SB_ROW + 6;
    }

    private int sidebarBottom() {
        return this.height - 30 - (updateLabel() != null ? RetroUi.CHIP_H + 4 : 0);
    }

    // ---------- rendering ----------

    @Override
    public void renderBackground(GuiGraphics g) {
        g.fill(0, 0, this.width, this.height, RetroUi.PAGE);
        g.fill(sidebarW() - 1, 0, sidebarW(), this.height, RetroUi.SHADOW);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderSidebar(g, mouseX, mouseY);
    }

    private void renderSidebar(GuiGraphics g, int mouseX, int mouseY) {
        int sw = sidebarW();
        String active = navId();
        RetroUi.drawBlinkingTitle(g, this.font, "MineFeed", 6, TITLE_Y, RetroUi.ACCENT);
        int y = SB_TOP;
        for (Item it : topItems()) {
            drawItem(g, it, y, mouseX, mouseY, sw, it.id() != null && it.id().equals(active));
            y += SB_ROW;
        }
        int ct = communitiesTop();
        g.drawString(this.font, tr("feed.nav.communities"), 8, ct, RetroUi.GRAY, false);
        int listTop = ct + 11;
        int bottom = sidebarBottom();
        g.enableScissor(0, listTop, sw - 1, bottom);
        y = listTop - sbScroll;
        boolean inList = mouseY >= listTop && mouseY < bottom;
        for (Item it : communityItems()) {
            if (y + SB_ROW > listTop && y < bottom) {
                drawItem(g, it, y, mouseX, inList ? mouseY : -1, sw, it.id() != null && it.id().equals(active));
            }
            y += SB_ROW;
        }
        g.disableScissor();

        String upd = updateLabel();
        if (upd != null) {
            RetroUi.drawChip(g, this.font, FeedUtil.clip(this.font, upd, sw - 20), 6, bottom + 2,
                    UpdateChecker.state() == UpdateChecker.State.OUTDATED ? RetroUi.ERROR : RetroUi.ACCENT, false);
        }
        int by = this.height - 24;
        g.fill(6, by - 5, sw - 6, by - 4, RetroUi.SHADOW);
        boolean hover = mouseX >= 0 && mouseX < sw - 1 && mouseY >= by - 3 && mouseY < this.height;
        if (hover) {
            g.fill(4, by - 3, sw - 5, this.height - 3, RetroUi.HOVER_BG);
        }
        AccountApi.Account a = AccountManager.INSTANCE.account();
        String nick = a == null ? "?" : a.nick();
        RetroUi.drawAvatar(g, this.font, FeedApiClient.avatarKey(a == null ? null : a.id(), null), nick, 8, by, 16);
        g.drawString(this.font, FeedUtil.clip(this.font, nick, sw - 36), 28, by, hover ? RetroUi.STRONG : RetroUi.FG, false);
        g.drawString(this.font, FeedUtil.clip(this.font, "@" + (a == null ? "?" : a.tag()), sw - 36), 28, by + 9, RetroUi.GRAY, false);
    }

    private void drawItem(GuiGraphics g, Item it, int y, int mouseX, int mouseY, int sw, boolean active) {
        int x = 4;
        int w = sw - 10;
        boolean hover = it.action() != null && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H;
        if (active || hover) {
            g.fill(x, y, x + w, y + ROW_H, RetroUi.HOVER_BG);
        }
        if (active) {
            g.fill(x, y, x + 2, y + ROW_H, RetroUi.ACCENT);
        }
        int base = it.color() != 0 ? it.color() : (it.dim() ? RetroUi.GRAY : active || hover ? RetroUi.FG : RetroUi.GRAY);
        int labelColor = active ? RetroUi.STRONG : hover && it.color() == 0 ? RetroUi.STRONG : base;
        PixelIcon.draw(g, it.icon(), x + 6, y + 3, active ? RetroUi.ACCENT : base);
        int badgeW = it.badge() == null ? 0 : this.font.width(it.badge()) + 5;
        g.drawString(this.font, FeedUtil.clip(this.font, it.label(), w - 22 - badgeW), x + 17, y + 3, labelColor, false);
        if (it.badge() != null) {
            int bx = x + w - badgeW - 2;
            g.fill(bx, y + 2, bx + badgeW, y + 11, it.badgeColor());
            g.drawString(this.font, it.badge(), bx + 3, y + 3, RetroUi.BG, false);
        }
    }

    // ---------- input ----------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX < sidebarW()) {
            if (super.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            clickSidebar(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dy) {
        if (mouseX < sidebarW()) {
            int listTop = communitiesTop() + 11;
            int total = communityItems().size() * SB_ROW;
            int max = Math.max(0, total - (sidebarBottom() - listTop));
            sbScroll = Math.max(0, Math.min(max, sbScroll - (int) (dy * SB_ROW)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dy);
    }

    private void clickSidebar(double mouseY) {
        int y = SB_TOP;
        for (Item it : topItems()) {
            if (mouseY >= y && mouseY < y + ROW_H) {
                if (it.action() != null) {
                    it.action().run();
                }
                return;
            }
            y += SB_ROW;
        }
        int listTop = communitiesTop() + 11;
        int bottom = sidebarBottom();
        if (mouseY >= listTop && mouseY < bottom) {
            y = listTop - sbScroll;
            for (Item it : communityItems()) {
                if (mouseY >= y && mouseY < y + ROW_H) {
                    if (it.action() != null) {
                        it.action().run();
                    }
                    return;
                }
                y += SB_ROW;
            }
        }
        if (updateLabel() != null && UpdateChecker.downloadUrl() != null && mouseY >= bottom + 2 && mouseY < bottom + 2 + RetroUi.CHIP_H) {
            ConfirmLinkScreen.confirmLinkNow(UpdateChecker.downloadUrl(), this, false);
            return;
        }
        if (mouseY >= this.height - 27) {
            openPlayer(ownId());
        }
    }

    // ---------- helpers ----------

    /** Page title with an optional gray tail: "Уведомления · 3 новых". */
    protected void drawTitle(GuiGraphics g, String title, String tail, int x, int y, int maxRight) {
        String t = FeedUtil.clip(this.font, title, Math.max(20, maxRight - x));
        g.drawString(this.font, t, x, y, RetroUi.FG, false);
        if (tail != null) {
            String s = " \u00b7 " + tail;
            int sx = x + this.font.width(t);
            if (sx + this.font.width(s) < maxRight) {
                g.drawString(this.font, s, sx, y, RetroUi.GRAY, false);
            }
        }
    }

    protected void drawScrollbar(GuiGraphics g, int top, int bottom, int scroll, int contentHeight) {
        int viewport = bottom - top;
        if (contentHeight <= viewport) {
            return;
        }
        int x = Math.min(this.width - 4, contentLeft() + contentWidth() + 6);
        int barH = Math.max(16, viewport * viewport / contentHeight);
        int barY = top + (viewport - barH) * scroll / Math.max(1, contentHeight - viewport);
        g.fill(x, top, x + 2, bottom, RetroUi.SHADOW);
        g.fill(x, barY, x + 2, barY + barH, RetroUi.GRAY);
    }

    protected String tr(String key) {
        return Component.translatable(key).getString();
    }

    protected static String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    private String updateLabel() {
        return switch (UpdateChecker.state()) {
            case UPDATE_AVAILABLE -> Component.translatable("feed.update.chip", UpdateChecker.latest()).getString();
            case OUTDATED -> Component.translatable("feed.update.chip_outdated").getString();
            default -> null;
        };
    }

    @Override
    public void onClose() {
        if (this == home()) {
            super.onClose();
        } else {
            showHome();
        }
    }
}
