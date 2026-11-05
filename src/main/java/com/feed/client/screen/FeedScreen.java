package com.feed.client.screen;

import com.feed.client.AvatarCache;
import com.feed.client.CommunityApi;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedTime;
import com.feed.client.FeedUtil;
import com.feed.client.NotificationPoller;
import com.feed.client.ReactionHelper;
import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.UpdateChecker;
import com.feed.data.Post;
import com.feed.data.PostType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Main screen: sidebar (feed, notifications, profile, communities) + the feed of the selected space.
 */
@OnlyIn(Dist.CLIENT)
public class FeedScreen extends RetroScreen {

    private static final int PAGE_SIZE = 20;
    private static final int PAD = 8;
    private static final int TAB_GAP = 6;
    private static final int CARD_GAP = 10;
    private static final int SCROLL_STEP = 18;
    private static final int LOAD_MORE_THRESHOLD = 40;
    private static final int TITLE_Y = 8;
    private static final int TABS_Y = 24;
    private static final int COMPOSE_Y = 42;
    private static final int COMPOSE_H = 20;
    private static final int LIST_TOP = COMPOSE_Y + COMPOSE_H + 8;
    private static final int SB_ROW = 14;
    private static final int SB_TOP = 26;
    private static final long LIVE_POLL_MS = 30_000L;
    private static final long COMMUNITY_POLL_MS = 30_000L;

    private final List<Post> posts = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    /** "global" or a community id. */
    private String space = "global";
    private PostType filter;
    private boolean mineOnly;
    private String nextCursor;
    private boolean loading;
    private boolean livePolling;
    private long lastLivePoll;
    private long lastCommunityPoll;
    private Component error;
    private int scroll;
    private int contentHeight;
    private int pendingNewAbove;
    private int sbScroll;
    private RetroButton manageButton;

    private record Row(Post post, List<String> lines, int y, int height) {}

    /** One clickable line in the sidebar. */
    private record Item(String label, int color, boolean active, String badge, int badgeColor, Runnable action) {}

    private record Tab(String label, int color, boolean active, PostType type, boolean mine) {}

    public FeedScreen() {
        super(Component.translatable("feed.brand.title"));
    }

    /** Hashtags are gone in v2; kept for old call sites. */
    public FeedScreen(String ignoredTag) {
        this();
    }

    public String space() {
        return space;
    }

    public CommunityApi.Community community() {
        return "global".equals(space) ? null : CommunityApi.cachedById(space);
    }

    // ---------- lifecycle ----------

    @Override
    protected void init() {
        if (!AccountManager.INSTANCE.hasAccount()) {
            // Defer: switching screens inside init() would re-enter Minecraft.setScreen.
            this.minecraft.tell(() -> {
                if (this.minecraft.screen == this) {
                    this.minecraft.setScreen(new WelcomeScreen(this));
                }
            });
            return;
        }
        AccountManager.INSTANCE.refreshOnce();
        UpdateChecker.checkOnce();

        manageButton = addRenderableWidget(new RetroButton(this.width - 8 - 70, 5, 70, 14,
                Component.translatable("feed.space.manage"), b -> {
            CommunityApi.Community c = community();
            if (c != null) {
                this.minecraft.setScreen(new CommunityScreen(this, c));
            }
        }));
        updateManageButton();

        relayout();
        if (System.currentTimeMillis() - lastCommunityPoll > 5_000L) {
            loadCommunities();
        }
        if (posts.isEmpty() && !loading) {
            refresh();
        }
    }

    private void updateManageButton() {
        if (manageButton == null) {
            return;
        }
        CommunityApi.Community c = community();
        manageButton.visible = c != null;
        if (c != null) {
            Component label = Component.translatable(c.isMod() ? "feed.space.manage" : "feed.space.info");
            int w = this.font.width(label) + 14;
            manageButton.setMessage(label);
            manageButton.setWidth(w);
            manageButton.setX(this.width - 8 - w);
        }
    }

    public void loadCommunities() {
        lastCommunityPoll = System.currentTimeMillis();
        CommunityApi.mine().whenComplete((m, ex) -> this.minecraft.execute(() -> {
            if (ex != null || m == null) {
                return;
            }
            if (!"global".equals(space) && CommunityApi.cachedById(space) == null) {
                switchSpace("global"); // left, kicked or deleted
            }
            updateManageButton();
        }));
    }

    @Override
    public void tick() {
        super.tick();
        if (!AccountManager.INSTANCE.hasAccount()) {
            this.minecraft.setScreen(new WelcomeScreen(this));
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCommunityPoll > COMMUNITY_POLL_MS) {
            loadCommunities();
        }
        if (loading || livePolling || posts.isEmpty()) {
            return;
        }
        if (now - lastLivePoll < LIVE_POLL_MS) {
            return;
        }
        lastLivePoll = now;
        pollLiveFeed();
    }

    public void switchSpace(String newSpace) {
        String s = newSpace == null ? "global" : newSpace;
        if (!s.equals(space)) {
            space = s;
            filter = null;
            mineOnly = false;
        }
        updateManageButton();
        refresh();
    }

    public void refresh() {
        posts.clear();
        nextCursor = null;
        scroll = 0;
        pendingNewAbove = 0;
        lastLivePoll = System.currentTimeMillis();
        relayout();
        requestPage(null);
    }

    private void requestPage(String cursor) {
        loading = true;
        error = null;
        String forSpace = space;
        FeedApiClient.INSTANCE.fetchFeed(forSpace, cursor, PAGE_SIZE, mineOnly, false)
                .whenComplete((page, ex) -> this.minecraft.execute(() -> {
                    if (!forSpace.equals(space)) {
                        return; // switched meanwhile
                    }
                    loading = false;
                    lastLivePoll = System.currentTimeMillis();
                    if (ex != null) {
                        error = FeedApiClient.unwrap(ex).userMessage();
                        return;
                    }
                    if (page.notModified() || page.body() == null) {
                        return;
                    }
                    var resp = page.body();
                    if (resp.posts != null) {
                        posts.addAll(resp.posts);
                    }
                    nextCursor = resp.nextCursor;
                    relayout();
                    if (cursor == null && !"global".equals(space)) {
                        loadCommunities(); // opening a community clears its unread badge
                    }
                }));
    }

    private void pollLiveFeed() {
        livePolling = true;
        String forSpace = space;
        FeedApiClient.INSTANCE.fetchFeed(forSpace, null, PAGE_SIZE, mineOnly, true)
                .whenComplete((page, ex) -> this.minecraft.execute(() -> {
                    livePolling = false;
                    if (!forSpace.equals(space) || ex != null || page == null || page.notModified()) {
                        return;
                    }
                    if (page.body() == null || page.body().posts == null) {
                        return;
                    }
                    mergeLiveFeed(page.body().posts);
                }));
    }

    private void mergeLiveFeed(List<Post> page) {
        Map<String, Post> known = new HashMap<>();
        for (Post p : posts) {
            if (p.id != null) {
                known.put(p.id, p);
            }
        }
        List<Post> brandNew = new ArrayList<>();
        boolean updated = false;
        for (Post incoming : page) {
            if (incoming.id == null) {
                continue;
            }
            Post existing = known.get(incoming.id);
            if (existing == null) {
                brandNew.add(incoming);
                continue;
            }
            if (existing.commentCount != incoming.commentCount
                    || existing.solved != incoming.solved
                    || !Objects.equals(existing.myReaction, incoming.myReaction)) {
                existing.commentCount = incoming.commentCount;
                existing.solved = incoming.solved;
                existing.myReaction = incoming.myReaction;
                updated = true;
            }
            if (incoming.reactions != null) {
                existing.reactions.clear();
                existing.reactions.putAll(incoming.reactions);
                updated = true;
            }
        }
        if (brandNew.isEmpty()) {
            if (updated) {
                relayout();
            }
            return;
        }
        boolean atTop = scroll <= SCROLL_STEP;
        int oldScroll = scroll;
        int oldHeight = contentHeight;
        posts.addAll(0, brandNew);
        relayout();
        if (!atTop) {
            pendingNewAbove += brandNew.size();
            scroll = oldScroll + (contentHeight - oldHeight);
            clampScroll();
        }
    }

    private void relayout() {
        rows.clear();
        int textW = contentWidth() - PAD * 2;
        int y = 0;
        for (Post post : posts) {
            if (filter != null && post.postType() != filter) {
                continue;
            }
            List<String> lines = RetroUi.wrap(this.font, post.text != null ? post.text : "", textW);
            int lineStep = this.font.lineHeight + 3;
            int h = PAD + 16 + 6 + lines.size() * lineStep + 6 + RetroUi.CHIP_H + PAD + RetroUi.SHADOW_OFFSET;
            if (post.imageUrl != null) {
                h += imageSize(post, textW)[1] + 6;
            }
            rows.add(new Row(post, lines, y, h));
            y += h + CARD_GAP;
        }
        contentHeight = y;
        clampScroll();
    }

    // ---------- geometry ----------

    private int sidebarW() {
        return Math.max(92, Math.min(132, this.width / 4));
    }

    @Override
    protected int contentWidth() {
        return Math.min(420, this.width - sidebarW() - 24);
    }

    @Override
    protected int contentLeft() {
        int sb = sidebarW();
        return sb + (this.width - sb - contentWidth()) / 2;
    }

    private int listBottom() {
        return this.height - 6;
    }

    // ---------- rendering ----------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, RetroUi.BG);
        g.fill(sidebarW(), 0, sidebarW() + 1, this.height, RetroUi.SHADOW);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderSidebar(g, mouseX, mouseY);
        renderHeader(g, mouseX, mouseY);
        renderList(g, mouseX, mouseY);
    }

    private List<Item> topItems() {
        List<Item> items = new ArrayList<>();
        int unread = NotificationPoller.unreadCount();
        items.add(new Item(tr("feed.nav.feed"), RetroUi.FG, "global".equals(space), null, 0, () -> switchSpace("global")));
        items.add(new Item(tr("feed.nav.notifications"), RetroUi.FG, false, unread > 0 ? String.valueOf(unread) : null,
                RetroUi.ACCENT, () -> this.minecraft.setScreen(new NotificationsScreen(this))));
        items.add(new Item(tr("feed.nav.profile"), RetroUi.FG, false, null, 0,
                () -> this.minecraft.setScreen(new ProfileScreen(this))));
        items.add(new Item(tr("feed.nav.discord"), RetroUi.FG, false, null, 0,
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
                    badge = String.valueOf(c.unread());
                }
                String id = c.id();
                items.add(new Item((c.isOwner() ? "\u2605 " : "") + c.name(), RetroUi.FG, id.equals(space), badge, badgeColor,
                        () -> switchSpace(id)));
            }
            for (CommunityApi.Pending p : m.pending()) {
                items.add(new Item("\u231b " + p.name(), RetroUi.GRAY, false, null, 0, null));
            }
        }
        items.add(new Item(tr("feed.nav.join"), RetroUi.ACCENT, false, null, 0,
                () -> this.minecraft.setScreen(new JoinCodeScreen(this))));
        CommunityApi.Application app = m == null ? null : m.application();
        boolean owns = m != null && m.communities().stream().anyMatch(CommunityApi.Community::isOwner);
        if (app != null && app.pending()) {
            items.add(new Item(tr("feed.nav.applied"), RetroUi.GRAY, false, null, 0,
                    () -> this.minecraft.setScreen(new ApplyScreen(this))));
        } else if (!owns) {
            items.add(new Item(tr("feed.nav.apply"), RetroUi.ACCENT, false, null, 0,
                    () -> this.minecraft.setScreen(new ApplyScreen(this))));
        }
        return items;
    }

    private int communitiesTop() {
        return SB_TOP + topItems().size() * SB_ROW + 8;
    }

    private int sidebarBottom() {
        return this.height - 30 - (updateLabel() != null ? RetroUi.CHIP_H + 4 : 0);
    }

    private void renderSidebar(GuiGraphics g, int mouseX, int mouseY) {
        int sw = sidebarW();
        RetroUi.drawBlinkingTitle(g, this.font, "MineFeed", 6, TITLE_Y, RetroUi.ACCENT);
        int y = SB_TOP;
        for (Item it : topItems()) {
            drawItem(g, it, y, mouseX, mouseY, sw);
            y += SB_ROW;
        }
        int ct = communitiesTop();
        g.drawString(this.font, tr("feed.nav.communities"), 8, ct, RetroUi.GRAY, false);
        int listTop = ct + 12;
        int bottom = sidebarBottom();
        g.enableScissor(0, listTop, sw, bottom);
        y = listTop - sbScroll;
        for (Item it : communityItems()) {
            if (y + SB_ROW > listTop && y < bottom) {
                drawItem(g, it, y, mouseX, mouseY >= listTop && mouseY < bottom ? mouseY : -1, sw);
            }
            y += SB_ROW;
        }
        g.disableScissor();

        String upd = updateLabel();
        if (upd != null) {
            RetroUi.drawChip(g, this.font, FeedUtil.clip(this.font, upd, sw - 20), 6, bottom + 2,
                    UpdateChecker.state() == UpdateChecker.State.OUTDATED ? RetroUi.ERROR : RetroUi.ACCENT, false);
        }
        int py = this.height - 26;
        g.fill(6, py - 4, sw - 6, py - 3, RetroUi.SHADOW);
        boolean hover = mouseX >= 0 && mouseX < sw && mouseY >= py - 2 && mouseY < this.height;
        if (hover) {
            g.fill(2, py - 2, sw - 2, this.height - 2, RetroUi.HOVER_BG);
        }
        AccountApi.Account a = AccountManager.INSTANCE.account();
        String nick = a == null ? "?" : a.nick();
        drawAvatar(g, FeedApiClient.avatarKey(a == null ? null : a.id(), nick), nick, 6, py + 1, 16);
        g.drawString(this.font, FeedUtil.clip(this.font, nick, sw - 34), 26, py, RetroUi.FG, false);
        g.drawString(this.font, FeedUtil.clip(this.font, "@" + (a == null ? "?" : a.tag()), sw - 34), 26, py + 10, RetroUi.GRAY, false);
    }

    private void drawItem(GuiGraphics g, Item it, int y, int mouseX, int mouseY, int sw) {
        boolean hover = it.action() != null && mouseX >= 0 && mouseX < sw && mouseY >= y - 2 && mouseY < y + SB_ROW - 2;
        if (it.active() || hover) {
            g.fill(4, y - 2, sw - 4, y + SB_ROW - 3, RetroUi.HOVER_BG);
        }
        if (it.active()) {
            g.fill(4, y - 2, 6, y + SB_ROW - 3, RetroUi.ACCENT);
        }
        int badgeW = it.badge() == null ? 0 : this.font.width(it.badge()) + 6;
        String label = FeedUtil.clip(this.font, it.label(), sw - 18 - badgeW);
        int color = (it.active() || hover) && it.color() == RetroUi.FG ? 0xFFFFFFFF : it.color();
        g.drawString(this.font, label, 10, y + 1, color, false);
        if (it.badge() != null) {
            int bx = sw - 8 - badgeW;
            g.fill(bx, y - 1, bx + badgeW, y + 10, it.badgeColor());
            g.drawString(this.font, it.badge(), bx + 3, y + 1, RetroUi.BG, false);
        }
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        int x = contentLeft();
        CommunityApi.Community c = community();
        String title;
        String sub;
        if (c == null) {
            title = tr("feed.space.global");
            sub = tr("feed.space.global_sub");
        } else {
            title = c.name();
            sub = Component.translatable("feed.space.members", c.members()).getString();
        }
        int right = manageButton != null && manageButton.visible ? manageButton.getX() - 6 : this.width - 8;
        String t = FeedUtil.clip(this.font, title, Math.max(20, right - x));
        g.drawString(this.font, t, x, TITLE_Y, RetroUi.FG, false);
        int sx = x + this.font.width(t) + 4;
        if (sx + this.font.width("\u00b7 " + sub) < right) {
            g.drawString(this.font, "\u00b7 " + sub, sx, TITLE_Y, RetroUi.GRAY, false);
        }

        int tx = x;
        for (Tab tab : tabs()) {
            RetroUi.drawChip(g, this.font, tab.label(), tx, TABS_Y, tab.color(), tab.active());
            tx += RetroUi.chipWidth(this.font, tab.label()) + TAB_GAP;
        }

        int w = contentWidth();
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= COMPOSE_Y && mouseY < COMPOSE_Y + COMPOSE_H;
        RetroUi.drawBox(g, x, COMPOSE_Y, w, COMPOSE_H, hover);
        AccountApi.Account a = AccountManager.INSTANCE.account();
        String nick = a == null ? "?" : a.nick();
        drawAvatar(g, FeedApiClient.avatarKey(a == null ? null : a.id(), nick), nick, x + 4, COMPOSE_Y + 4, 12);
        String hint = Component.translatable("feed.compose.bar", nick).getString();
        g.drawString(this.font, FeedUtil.clip(this.font, hint, w - 30), x + 22, COMPOSE_Y + 6, hover ? RetroUi.FG : RetroUi.GRAY, false);
    }

    private List<Tab> tabs() {
        List<Tab> list = new ArrayList<>();
        list.add(new Tab(tr("feed.screen.feed.tab.all"), RetroUi.FG, filter == null && !mineOnly, null, false));
        for (PostType type : PostType.values()) {
            list.add(new Tab(type.prefix, type.accent, filter == type, type, false));
        }
        list.add(new Tab(tr("feed.screen.feed.tab.mine"), RetroUi.ACCENT, mineOnly, null, true));
        return list;
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        int top = LIST_TOP;
        int bottom = listBottom();
        int cx = contentLeft();
        int mid = cx + contentWidth() / 2;
        g.enableScissor(sidebarW() + 1, top, this.width, bottom);

        for (Row row : rows) {
            int y = top + row.y() - scroll;
            if (y + row.height() >= top && y <= bottom) {
                boolean hovered = mouseX >= cx && mouseX < cx + contentWidth()
                        && mouseY >= Math.max(y, top) && mouseY < Math.min(y + row.height(), bottom);
                drawCard(g, row, cx, y, hovered);
            }
        }

        if (rows.isEmpty() && !loading && error == null) {
            String key;
            if (mineOnly) {
                key = "feed.screen.feed.empty.mine";
            } else if (filter != null) {
                key = filter.emptyKey();
            } else if (!"global".equals(space)) {
                key = "feed.screen.feed.empty.community";
            } else {
                key = "feed.screen.feed.empty";
            }
            g.drawCenteredString(this.font, Component.translatable(key), mid, top + 20, RetroUi.GRAY);
        }
        if (loading) {
            String load = tr("feed.screen.loading") + (RetroUi.blinkFast() ? "_" : " ");
            g.drawCenteredString(this.font, load, mid, bottom - 14, RetroUi.GRAY);
        }
        if (error != null) {
            g.drawCenteredString(this.font, error, mid, bottom - 14, RetroUi.ERROR);
        }
        g.disableScissor();

        if (pendingNewAbove > 0) {
            String label = newAboveLabel();
            int lw = RetroUi.chipWidth(this.font, label);
            int lx = mid - lw / 2;
            boolean hover = mouseX >= lx && mouseX < lx + lw && mouseY >= top + 2 && mouseY < top + 2 + RetroUi.CHIP_H;
            RetroUi.drawChip(g, this.font, label, lx, top + 2, RetroUi.ACCENT, !hover);
        }

        int viewport = bottom - top;
        if (contentHeight > viewport) {
            int barH = Math.max(12, viewport * viewport / contentHeight);
            int barY = top + (viewport - barH) * scroll / Math.max(1, contentHeight - viewport);
            g.fill(this.width - 4, barY, this.width - 2, barY + barH, RetroUi.GRAY);
        }
    }

    private String newAboveLabel() {
        return Component.translatable("feed.screen.feed.new_above", pendingNewAbove).getString();
    }

    private void drawAvatar(GuiGraphics g, String key, String name, int x, int y, int size) {
        ResourceLocation avatar = AvatarCache.get(key);
        if (avatar != null) {
            g.blit(avatar, x, y, 0, 0, size, size, size, size);
        } else {
            g.fill(x, y, x + size, y + size, RetroUi.GRAY);
            String letter = name == null || name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
            g.drawString(this.font, letter, x + (size - this.font.width(letter)) / 2 + 1, y + (size - 8) / 2, RetroUi.BG, false);
        }
    }

    private static final int CARD_IMAGE_H = 140;

    private static int[] imageSize(Post post, int textW) {
        return ImageBox.fit(post.imageW, post.imageH, textW, CARD_IMAGE_H);
    }

    private void drawCard(GuiGraphics g, Row row, int x, int y, boolean hovered) {
        Post post = row.post();
        PostType type = post.postType();
        int w = contentWidth();
        RetroUi.drawBox(g, x, y, w, row.height(), hovered);
        int fg = RetroUi.FG;
        int gray = RetroUi.GRAY;

        int ax = x + PAD;
        int ay = y + PAD;
        drawAvatar(g, FeedApiClient.avatarKey(post.uuid, post.author), displayAuthor(post), ax, ay, 16);
        g.renderOutline(ax, ay, 16, 16, fg);

        int nx = ax + 22;
        boolean own = FeedApiClient.isMine(post.uuid);
        String chip = post.displayPrefix();
        int chipW = RetroUi.chipWidth(this.font, chip);
        String shownName = FeedUtil.clip(this.font, displayAuthor(post), w - PAD * 2 - 22 - chipW - 6);
        g.drawString(this.font, shownName, nx, ay, own ? RetroUi.ACCENT : fg, false);
        String handle = post.fromDiscord ? "Discord" : (post.authorTag != null ? "@" + post.authorTag : "");
        int hx = nx + this.font.width(shownName) + 4;
        if (!handle.isEmpty() && hx + this.font.width(handle) < x + w - PAD - chipW - 6) {
            g.drawString(this.font, handle, hx, ay, gray, false);
        }
        g.drawString(this.font, FeedTime.ago(post.createdAt), nx, ay + 9, gray, false);
        RetroUi.drawChip(g, this.font, chip, x + w - PAD - chipW, ay + 1, post.prefixColor(), false);

        int lineY = y + PAD + 16 + 6;
        for (String line : row.lines()) {
            g.drawString(this.font, line, x + PAD, lineY, fg, false);
            lineY += this.font.lineHeight + 3;
        }
        if (post.imageUrl != null) {
            int[] s = imageSize(post, w - PAD * 2);
            ImageBox.draw(g, this.font, post.imageUrl, x + PAD, lineY + 3, s[0], s[1]);
        }

        int cardBg = hovered ? RetroUi.HOVER_BG : RetroUi.BG;
        int by = y + row.height() - PAD - RetroUi.CHIP_H;
        int rx = x + PAD;
        for (String key : type.reactions()) {
            int count = post.reactionCount(key);
            EmojiIcon icon = EmojiIcon.byKey(key);
            RetroUi.drawReactionChip(g, this.font, icon, count, rx, by, type.accent, post.mine(key), cardBg);
            rx += RetroUi.reactionChipWidth(this.font, icon, String.valueOf(count)) + TAB_GAP;
        }
        String cc = "\u270e " + post.commentCount;
        g.drawString(this.font, cc, x + w - PAD - this.font.width(cc), by + 2, gray, false);
    }

    // ---------- input ----------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        if (mouseX < sidebarW()) {
            int listTop = communitiesTop() + 12;
            int total = communityItems().size() * SB_ROW;
            int max = Math.max(0, total - (sidebarBottom() - listTop));
            sbScroll = Math.max(0, Math.min(max, sbScroll - (int) (dy * SB_ROW)));
            return true;
        }
        scroll -= (int) (dy * SCROLL_STEP);
        if (scroll <= SCROLL_STEP) {
            pendingNewAbove = 0;
        }
        clampScroll();
        int viewport = listBottom() - LIST_TOP;
        if (!loading && nextCursor != null && scroll >= contentHeight - viewport - LOAD_MORE_THRESHOLD) {
            requestPage(nextCursor);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (mouseX < sidebarW()) {
            return clickSidebar(mouseY);
        }

        int x = contentLeft();
        if (mouseY >= TABS_Y && mouseY < TABS_Y + RetroUi.CHIP_H) {
            int tx = x;
            for (Tab tab : tabs()) {
                int w = RetroUi.chipWidth(this.font, tab.label());
                if (mouseX >= tx && mouseX < tx + w) {
                    if (tab.mine()) {
                        mineOnly = !mineOnly;
                        filter = null;
                        refresh();
                    } else {
                        setFilter(tab.type());
                    }
                    return true;
                }
                tx += w + TAB_GAP;
            }
        }
        if (mouseY >= COMPOSE_Y && mouseY < COMPOSE_Y + COMPOSE_H && mouseX >= x && mouseX < x + contentWidth()) {
            this.minecraft.setScreen(new ComposeScreen(this));
            return true;
        }

        int top = LIST_TOP;
        if (pendingNewAbove > 0) {
            int lw = RetroUi.chipWidth(this.font, newAboveLabel());
            int lx = x + contentWidth() / 2 - lw / 2;
            if (mouseX >= lx && mouseX < lx + lw && mouseY >= top + 2 && mouseY < top + 2 + RetroUi.CHIP_H) {
                scroll = 0;
                pendingNewAbove = 0;
                return true;
            }
        }
        if (mouseY >= top && mouseY < listBottom()) {
            for (Row row : rows) {
                int y = top + row.y() - scroll;
                if (mouseY < y || mouseY >= y + row.height()) {
                    continue;
                }
                if (mouseX < x || mouseX >= x + contentWidth()) {
                    break;
                }
                return clickInsideCard(row, x, y, mouseX, mouseY);
            }
        }
        return false;
    }

    private boolean clickSidebar(double mouseY) {
        int y = SB_TOP;
        for (Item it : topItems()) {
            if (mouseY >= y - 2 && mouseY < y + SB_ROW - 2) {
                if (it.action() != null) {
                    it.action().run();
                }
                return true;
            }
            y += SB_ROW;
        }
        int listTop = communitiesTop() + 12;
        int bottom = sidebarBottom();
        if (mouseY >= listTop && mouseY < bottom) {
            y = listTop - sbScroll;
            for (Item it : communityItems()) {
                if (mouseY >= y - 2 && mouseY < y + SB_ROW - 2) {
                    if (it.action() != null) {
                        it.action().run();
                    }
                    return true;
                }
                y += SB_ROW;
            }
        }
        if (updateLabel() != null && UpdateChecker.downloadUrl() != null && mouseY >= bottom + 2 && mouseY < bottom + 2 + RetroUi.CHIP_H) {
            ConfirmLinkScreen.confirmLinkNow(this, UpdateChecker.downloadUrl());
            return true;
        }
        if (mouseY >= this.height - 28) {
            this.minecraft.setScreen(new ProfileScreen(this));
            return true;
        }
        return false;
    }

    private void setFilter(PostType type) {
        boolean wasMine = mineOnly;
        filter = type;
        mineOnly = false;
        if (wasMine) {
            refresh();
        } else {
            scroll = 0;
            relayout();
        }
    }

    private boolean clickInsideCard(Row row, int x, int y, double mouseX, double mouseY) {
        Post post = row.post();
        int by = y + row.height() - PAD - RetroUi.CHIP_H;
        if (mouseY >= by && mouseY < by + RetroUi.CHIP_H) {
            int rx = x + PAD;
            for (String key : post.postType().reactions()) {
                int count = post.reactionCount(key);
                int w = RetroUi.reactionChipWidth(this.font, EmojiIcon.byKey(key), String.valueOf(count));
                if (mouseX >= rx && mouseX < rx + w) {
                    ReactionHelper.toggle(post, key, null);
                    return true;
                }
                rx += w + TAB_GAP;
            }
        }
        this.minecraft.setScreen(new PostScreen(this, post));
        return true;
    }

    private static String displayAuthor(Post post) {
        return post.author == null || post.author.isEmpty() ? "?" : post.author;
    }

    private void clampScroll() {
        int viewport = listBottom() - LIST_TOP;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewport)));
    }

    private String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** Null when no update notice should be shown. */
    private String updateLabel() {
        return switch (UpdateChecker.state()) {
            case UPDATE_AVAILABLE -> Component.translatable("feed.update.chip", UpdateChecker.latest()).getString();
            case OUTDATED -> Component.translatable("feed.update.chip_outdated").getString();
            default -> null;
        };
    }
}
