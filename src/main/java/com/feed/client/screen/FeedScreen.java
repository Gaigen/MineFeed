package com.feed.client.screen;

import com.feed.client.AvatarCache;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedTime;
import com.feed.client.NotificationPoller;
import com.feed.client.ReactionHelper;
import com.feed.data.Post;
import com.feed.data.PostType;
import net.minecraft.client.gui.GuiGraphics;
import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.UpdateChecker;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Main feed screen: type tabs, tag search, reaction chips.
 */
@OnlyIn(Dist.CLIENT)
public class FeedScreen extends RetroScreen {

    private static final int PAGE_SIZE = 20;
    private static final int HEADER_H = 64;
    private static final int TABS_Y = 36;
    private static final int TAB_GAP = 6;
    private static final int PAD = 8;
    private static final String DISCORD_LABEL = "DISCORD";
    private static final int CARD_GAP = 10;
    private static final int SCROLL_STEP = 18;
    private static final int LOAD_MORE_THRESHOLD = 40;
    private static final int STATS_H = 30;
    private static final int STATS_BLOCK = STATS_H + CARD_GAP;
    private static final int SEARCH_H = 18;
    private static final int SUGG_ROW_H = 12;
    private static final int HDR_Y = 6;
    private static final int HDR_RIGHT = 8;
    private static final int HDR_GAP = 6;
    private static final int NEW_BTN_W = 58;
    private static final int NEW_BTN_H = 18;
    private static final int SEARCH_BTN_W = 20;
    private static final int SEARCH_BTN_H = 14;
    private static final long LIVE_POLL_MS = 60_000L;

    private final List<Post> posts = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();

    private PostType filter;
    private String tagFilter;
    private boolean mineOnly;
    private String nextCursor;
    private boolean loading;
    private boolean livePolling;
    private long lastLivePoll;
    private Component error;
    private int scroll;
    private int contentHeight;

    private EditBox searchBox;
    private boolean searchOpen = false;
    private List<FeedApiClient.TagCount> suggestions = new ArrayList<>();
    private String lastSentQuery = null;
    private long lastTypedAt = 0L;
    private int pendingNewAbove = 0;

    private record Row(Post post, List<String> lines, int y, int height) {}

    public FeedScreen() {
        super(Component.translatable("feed.brand.title"));
    }

    public FeedScreen(String tag) {
        this();
        this.tagFilter = tag;
    }

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
        addRenderableWidget(new RetroButton(PAD, HDR_Y, profileButtonWidth(), SEARCH_BTN_H,
                Component.literal(profileLabel()), b -> this.minecraft.setScreen(new ProfileScreen(this))));
        int dcX = PAD + profileButtonWidth() + 4;
        int dcW = this.font.width(DISCORD_LABEL) + 10;
        int titleLeft = (this.width - this.font.width(this.title.getString() + "_")) / 2;
        if (dcX + dcW < titleLeft - 4) { // only when it doesn't collide with the title
            addRenderableWidget(new RetroButton(dcX, HDR_Y, dcW, SEARCH_BTN_H, Component.literal(DISCORD_LABEL),
                    b -> net.minecraft.Util.getPlatform().openUri(UpdateChecker.discordUrl())));
        }

        searchBox = new EditBox(this.font, contentLeft() + 2, TABS_Y + 20,
                contentWidth() - 4, 12, Component.translatable("feed.screen.feed.search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(32);
        searchBox.setVisible(false);
        searchBox.setResponder(s -> lastTypedAt = System.currentTimeMillis());
        addRenderableWidget(searchBox);

        addRenderableWidget(new RetroButton(hdrSearchX(), HDR_Y, SEARCH_BTN_W, SEARCH_BTN_H,
                Component.translatable("feed.screen.feed.search_btn"), b -> {
            searchOpen = !searchOpen;
            searchBox.setVisible(searchOpen);
            if (searchOpen) {
                setFocused(searchBox);
                lastSentQuery = null;
            }
        }));

        addRenderableWidget(new RetroButton(hdrNewX(), HDR_Y, NEW_BTN_W, NEW_BTN_H,
                Component.translatable("feed.screen.feed.new_post"), b -> this.minecraft.setScreen(new ComposeScreen(this))));

        relayout();
        if (posts.isEmpty() && !loading) {
            refresh();
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!AccountManager.INSTANCE.hasAccount()) {
            this.minecraft.setScreen(new WelcomeScreen(this));
            return;
        }
        if (searchOpen && System.currentTimeMillis() - lastTypedAt > 300) {
            String q = searchBox.getValue().trim().replace("#", "").toLowerCase();
            if (!q.equals(lastSentQuery)) {
                lastSentQuery = q;
                FeedApiClient.INSTANCE.fetchTags(q).thenAccept(tags ->
                        this.minecraft.execute(() ->
                                suggestions = tags == null ? new ArrayList<>() : new ArrayList<>(tags)));
            }
        }
        if (loading || livePolling || posts.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLivePoll < LIVE_POLL_MS) {
            return;
        }
        lastLivePoll = now;
        pollLiveFeed();
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
        FeedApiClient.INSTANCE.fetchFeed(cursor, PAGE_SIZE, tagFilter, mineOnly, false)
                .whenComplete((page, ex) -> this.minecraft.execute(() -> {
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
                }));
    }

    private void pollLiveFeed() {
        livePolling = true;
        FeedApiClient.INSTANCE.fetchFeed(null, PAGE_SIZE, tagFilter, mineOnly, true)
                .whenComplete((page, ex) -> this.minecraft.execute(() -> {
                    livePolling = false;
                    if (ex != null || page == null || page.notModified()) {
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
        posts.addAll(0, brandNew);
        if (!atTop) {
            pendingNewAbove += brandNew.size();
        }
        relayout();
        if (!atTop) {
            int bump = 0;
            int n = Math.min(brandNew.size(), rows.size());
            for (int i = 0; i < n; i++) {
                bump += rows.get(i).height() + CARD_GAP;
            }
            scroll = oldScroll + bump;
            clampScroll();
        }
    }

    private void relayout() {
        rows.clear();
        int textW = contentWidth() - PAD * 2;
        int y = STATS_BLOCK;
        for (Post post : posts) {
            if (filter != null && post.postType() != filter) {
                continue;
            }
            List<String> lines = RetroUi.wrap(this.font, post.text != null ? post.text : "", textW);
            int lineStep = this.font.lineHeight + 3;
            int h = PAD + 16 + 6 + lines.size() * lineStep
                    + 6 + RetroUi.CHIP_H + PAD + RetroUi.SHADOW_OFFSET;
            rows.add(new Row(post, lines, y, h));
            y += h + CARD_GAP;
        }
        contentHeight = y;
        clampScroll();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, RetroUi.BG);
        g.fill(0, listTop() - 5, this.width, listTop() - 4, RetroUi.SHADOW);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderHeader(g);
        if (searchOpen) {
            renderSearch(g, mouseX, mouseY);
        }
        renderList(g, mouseX, mouseY);

        if (updateLabel() != null) {
            RetroUi.drawChip(g, this.font, updateLabel(), PAD, updateChipY(),
                    UpdateChecker.state() == UpdateChecker.State.OUTDATED ? RetroUi.ERROR : RetroUi.ACCENT, false);
        }

        String label = "!" + NotificationPoller.unreadCount();
        RetroUi.drawChip(g, this.font, label, hdrNotifX(label), HDR_Y,
                NotificationPoller.hasUnread() ? RetroUi.ERROR : RetroUi.GRAY, NotificationPoller.hasUnread());
    }

    private void renderHeader(GuiGraphics g) {
        String title = this.title.getString() + (RetroUi.blink() ? "_" : " ");
        g.drawString(this.font, title, (this.width - this.font.width(title)) / 2, 8, RetroUi.FG, false);
        String tag = Component.translatable("feed.brand.tagline").getString();
        g.drawString(this.font, tag, (this.width - this.font.width(tag)) / 2, 18, RetroUi.GRAY, false);

        String all = Component.translatable("feed.screen.feed.tab.all").getString();
        String mine = Component.translatable("feed.screen.feed.tab.mine").getString();
        int x = tabsStartX();
        boolean allActive = filter == null && !mineOnly;
        RetroUi.drawChip(g, this.font, all, x, TABS_Y, RetroUi.FG, allActive);
        x += RetroUi.chipWidth(this.font, all) + TAB_GAP;
        RetroUi.drawChip(g, this.font, mine, 8, TABS_Y, RetroUi.ACCENT, mineOnly);
        for (PostType type : PostType.values()) {
            RetroUi.drawChip(g, this.font, type.prefix, x, TABS_Y, type.accent, filter == type);
            x += RetroUi.chipWidth(this.font, type.prefix) + TAB_GAP;
        }

        if (tagFilter != null) {
            RetroUi.drawChip(g, this.font, tagLabel(), contentLeft(), HEADER_H, RetroUi.ACCENT, true);
        }
    }

    private void renderSearch(GuiGraphics g, int mouseX, int mouseY) {
        int sx = contentLeft();
        int sy = TABS_Y + 18;
        RetroUi.drawBox(g, sx, sy, contentWidth(), SEARCH_H, false);

        if (searchBox.getValue().isBlank() && !suggestions.isEmpty()) {
            g.drawString(this.font, Component.translatable("feed.screen.feed.trending"),
                    sx + 2, sy + SEARCH_H + 2, RetroUi.GRAY, false);
        }

        int rowY = sy + SEARCH_H + (searchBox.getValue().isBlank() ? 12 : 2);
        for (FeedApiClient.TagCount tc : suggestions) {
            String row = "#" + tc.tag() + " (" + tc.count() + ")";
            boolean hover = mouseX >= sx && mouseX <= sx + contentWidth()
                    && mouseY >= rowY && mouseY < rowY + SUGG_ROW_H;
            if (hover) {
                g.fill(sx, rowY, sx + contentWidth(), rowY + SUGG_ROW_H, RetroUi.HOVER_BG);
            }
            g.drawString(this.font, row, sx + 4, rowY + 2,
                    hover ? RetroUi.ACCENT : RetroUi.FG, false);
            rowY += SUGG_ROW_H;
        }
    }

    private int tabsStartX() {
        String all = Component.translatable("feed.screen.feed.tab.all").getString();
        int total = RetroUi.chipWidth(this.font, all);
        for (PostType type : PostType.values()) {
            total += TAB_GAP + RetroUi.chipWidth(this.font, type.prefix);
        }
        return (this.width - total) / 2;
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        int top = listTop();
        int bottom = this.height - 8;
        g.enableScissor(0, top, this.width, bottom);

        drawStats(g, contentLeft(), top - scroll);

        if (pendingNewAbove > 0) {
            String label = Component.translatable("feed.screen.feed.new_above", pendingNewAbove).getString();
            int lw = this.font.width(label);
            int lx = this.width / 2 - lw / 2;
            int ny = top + STATS_BLOCK - scroll - 14;
            boolean hover = mouseX >= lx - 4 && mouseX <= lx + lw + 4
                    && mouseY >= ny - 2 && mouseY <= ny + 10;
            if (hover) {
                g.fill(lx - 4, ny - 2, lx + lw + 4, ny + 10, RetroUi.HOVER_BG);
            }
            g.drawString(this.font, label, lx, ny, hover ? RetroUi.ACCENT : RetroUi.FG, false);
        }

        for (Row row : rows) {
            int y = top + row.y() - scroll;
            if (y + row.height() >= top && y <= bottom) {
                boolean hovered = mouseX >= contentLeft()
                        && mouseX < contentLeft() + contentWidth()
                        && mouseY >= Math.max(y, top)
                        && mouseY < Math.min(y + row.height(), bottom);
                drawCard(g, row, contentLeft(), y, hovered);
            }
        }

        if (rows.isEmpty() && !loading && error == null) {
            String key;
            if (mineOnly) {
                key = "feed.screen.feed.empty.mine";
            } else if (filter != null) {
                key = filter.emptyKey();
            } else {
                key = "feed.screen.feed.empty";
            }
            g.drawCenteredString(this.font, Component.translatable(key),
                    this.width / 2, top + STATS_BLOCK + 20, RetroUi.GRAY);
        }
        if (loading) {
            String load = Component.translatable("feed.screen.loading").getString()
                    + (RetroUi.blinkFast() ? "_" : " ");
            g.drawCenteredString(this.font, load, this.width / 2, bottom - 14, RetroUi.GRAY);
        }
        if (error != null) {
            g.drawCenteredString(this.font, error, this.width / 2, bottom - 14, RetroUi.ERROR);
        }
        g.disableScissor();

        int viewport = bottom - top;
        if (contentHeight > viewport) {
            int barH = Math.max(12, viewport * viewport / contentHeight);
            int barY = top + (viewport - barH) * scroll / Math.max(1, contentHeight - viewport);
            g.fill(this.width - 4, barY, this.width - 2, barY + barH, RetroUi.GRAY);
        }
    }

    private void drawStats(GuiGraphics g, int x, int y) {
        int w = contentWidth();
        int half = (w - CARD_GAP) / 2;
        RetroUi.drawBox(g, x, y, half, STATS_H, false);
        RetroUi.drawBox(g, x + half + CARD_GAP, y, half, STATS_H, false);

        g.drawString(this.font, Component.translatable("feed.screen.feed.stats.posts"),
                x + 6, y + 5, RetroUi.GRAY, false);
        String count = rows.size() + (nextCursor != null ? "+" : "");
        g.drawString(this.font, count, x + 6, y + 16, RetroUi.FG, false);

        g.drawString(this.font, Component.translatable("feed.screen.feed.stats.top"),
                x + half + CARD_GAP + 6, y + 5, RetroUi.GRAY, false);
        g.drawString(this.font, topType(), x + half + CARD_GAP + 6, y + 16, RetroUi.FG, false);
    }

    private String topType() {
        Map<PostType, Integer> counts = new EnumMap<>(PostType.class);
        for (Post post : posts) {
            counts.merge(post.postType(), 1, Integer::sum);
        }
        PostType best = null;
        for (Map.Entry<PostType, Integer> e : counts.entrySet()) {
            if (best == null || e.getValue() > counts.get(best)) {
                best = e.getKey();
            }
        }
        return best == null ? "-" : best.prefix;
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
        ResourceLocation avatar = AvatarCache.get(FeedApiClient.avatarKey(post.uuid, post.author));
        if (avatar != null) {
            g.blit(avatar, ax, ay, 0, 0, 16, 16, 16, 16);
        } else {
            g.fill(ax, ay, ax + 16, ay + 16, gray);
            String letter = displayAuthor(post).substring(0, 1).toUpperCase();
            g.drawString(this.font, letter, ax + (16 - this.font.width(letter)) / 2, ay + 4,
                    RetroUi.BG, false);
        }
        g.renderOutline(ax, ay, 16, 16, fg);

        int nx = ax + 24;
        RetroUi.drawChip(g, this.font, post.displayPrefix(), nx, ay + 2, post.prefixColor(), false);
        int tx = nx + RetroUi.chipWidth(this.font, post.displayPrefix()) + 5;
        boolean own = isOwn(post);
        String name = displayAuthor(post);
        g.drawString(this.font, name, tx, ay + 4, own ? RetroUi.ACCENT : fg, false);
        g.drawString(this.font, "\u00b7 " + FeedTime.ago(post.createdAt),
                tx + this.font.width(name) + 4, ay + 4, gray, false);

        int lineY = y + PAD + 16 + 6;
        for (String line : row.lines()) {
            int lx = x + PAD;
            for (TagText.Seg seg : TagText.segments(line)) {
                g.drawString(this.font, seg.text(), lx, lineY, seg.tag() ? type.accent : fg, false);
                lx += this.font.width(seg.text());
            }
            lineY += this.font.lineHeight + 3;
        }

        int cardBg = hovered ? RetroUi.HOVER_BG : RetroUi.BG;
        int by = y + row.height() - PAD - RetroUi.CHIP_H;
        int rx = x + PAD;
        for (String key : type.reactions()) {
            int count = post.reactionCount(key);
            EmojiIcon icon = EmojiIcon.byKey(key);
            RetroUi.drawReactionChip(g, this.font, icon, count, rx, by,
                    type.accent, post.mine(key), cardBg);
            rx += RetroUi.reactionChipWidth(this.font, icon, String.valueOf(count)) + TAB_GAP;
        }
        String cc = "\u270e " + post.commentCount;
        g.drawString(this.font, cc, x + w - PAD - this.font.width(cc), by + 2, gray, false);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        scroll -= (int) (dy * SCROLL_STEP);
        if (scroll <= SCROLL_STEP) {
            pendingNewAbove = 0;
        }
        clampScroll();
        int viewport = this.height - 8 - listTop();
        if (!loading && nextCursor != null
                && scroll >= contentHeight - viewport - LOAD_MORE_THRESHOLD) {
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

        String upd = updateLabel();
        if (upd != null && UpdateChecker.downloadUrl() != null
                && mouseY >= updateChipY() && mouseY < updateChipY() + RetroUi.CHIP_H
                && mouseX >= PAD && mouseX < PAD + RetroUi.chipWidth(this.font, upd)) {
            ConfirmLinkScreen.confirmLinkNow(this, UpdateChecker.downloadUrl());
            return true;
        }

        String notifLabel = "!" + NotificationPoller.unreadCount();
        int nx = hdrNotifX(notifLabel);
        int nw = RetroUi.chipWidth(this.font, notifLabel);
        if (mouseY >= HDR_Y && mouseY < HDR_Y + RetroUi.CHIP_H
                && mouseX >= nx && mouseX < nx + nw) {
            this.minecraft.setScreen(new NotificationsScreen(this));
            return true;
        }

        if (mouseY >= TABS_Y && mouseY < TABS_Y + RetroUi.CHIP_H) {
            String mine = Component.translatable("feed.screen.feed.tab.mine").getString();
            int mw = RetroUi.chipWidth(this.font, mine);
            if (mouseX >= 8 && mouseX < 8 + mw) {
                mineOnly = !mineOnly;
                if (mineOnly) {
                    filter = null;
                }
                refresh();
                return true;
            }
        }

        if (mouseY >= TABS_Y && mouseY < TABS_Y + RetroUi.CHIP_H) {
            String all = Component.translatable("feed.screen.feed.tab.all").getString();
            int x = tabsStartX();
            int w = RetroUi.chipWidth(this.font, all);
            if (mouseX >= x && mouseX < x + w) {
                setFilter(null);
                return true;
            }
            x += w + TAB_GAP;
            for (PostType type : PostType.values()) {
                w = RetroUi.chipWidth(this.font, type.prefix);
                if (mouseX >= x && mouseX < x + w) {
                    setFilter(type);
                    return true;
                }
                x += w + TAB_GAP;
            }
        }

        if (tagFilter != null && mouseY >= HEADER_H && mouseY < HEADER_H + RetroUi.CHIP_H) {
            int w = RetroUi.chipWidth(this.font, tagLabel());
            if (mouseX >= contentLeft() && mouseX < contentLeft() + w) {
                tagFilter = null;
                refresh();
                return true;
            }
        }

        if (searchOpen) {
            int sx = contentLeft();
            int sy = TABS_Y + 18;
            int rowY = sy + SEARCH_H + (searchBox.getValue().isBlank() ? 12 : 2);
            for (FeedApiClient.TagCount tc : suggestions) {
                if (mouseX >= sx && mouseX <= sx + contentWidth()
                        && mouseY >= rowY && mouseY < rowY + SUGG_ROW_H) {
                    applyTag(tc.tag());
                    return true;
                }
                rowY += SUGG_ROW_H;
            }
        }

        int top = listTop();
        if (pendingNewAbove > 0 && mouseY >= top && mouseY < top + STATS_BLOCK) {
            String label = Component.translatable("feed.screen.feed.new_above", pendingNewAbove).getString();
            int lw = this.font.width(label);
            int lx = this.width / 2 - lw / 2;
            int ny = top + STATS_BLOCK - scroll - 14;
            if (mouseX >= lx - 4 && mouseX <= lx + lw + 4
                    && mouseY >= ny - 2 && mouseY <= ny + 10) {
                scroll = 0;
                pendingNewAbove = 0;
                clampScroll();
                return true;
            }
        }

        if (mouseY >= top && mouseY < this.height - 8) {
            for (Row row : rows) {
                int y = top + row.y() - scroll;
                if (mouseY < y || mouseY >= y + row.height()) {
                    continue;
                }
                if (mouseX < contentLeft() || mouseX >= contentLeft() + contentWidth()) {
                    break;
                }
                return clickInsideCard(row, contentLeft(), y, mouseX, mouseY);
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchOpen && getFocused() == searchBox
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            if (!suggestions.isEmpty()) {
                applyTag(suggestions.get(0).tag());
            } else if (!searchBox.getValue().isBlank()) {
                applyTag(searchBox.getValue().trim());
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void applyTag(String tag) {
        tagFilter = tag.toLowerCase().replace("#", "");
        searchOpen = false;
        searchBox.setVisible(false);
        suggestions = new ArrayList<>();
        refresh();
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

    private boolean isOwn(Post post) {
        return FeedApiClient.isMine(post.uuid);
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
        int bodyTop = y + PAD + 16 + 6;
        int lineH = this.font.lineHeight + 3;
        int index = (int) ((mouseY - bodyTop) / lineH);
        if (mouseY >= bodyTop && index >= 0 && index < row.lines().size()) {
            int lx = x + PAD;
            for (TagText.Seg seg : TagText.segments(row.lines().get(index))) {
                int w = this.font.width(seg.text());
                if (mouseX >= lx && mouseX < lx + w) {
                    if (seg.tag()) {
                        applyTag(seg.text().substring(1));
                        return true;
                    }
                    break;
                }
                lx += w;
            }
        }
        this.minecraft.setScreen(new PostScreen(this, post));
        return true;
    }

    private static String displayAuthor(Post post) {
        return post.author == null || post.author.isEmpty() ? "?" : post.author;
    }

    private String tagLabel() {
        return Component.translatable("feed.screen.feed.tag_clear", tagFilter).getString();
    }

    private void clampScroll() {
        int viewport = this.height - 8 - listTop();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewport)));
    }

    private int listTop() {
        int extra = tagFilter == null ? 0 : RetroUi.CHIP_H + 6;
        if (searchOpen) {
            extra += SEARCH_H + 4;
            if (searchBox != null && searchBox.getValue().isBlank() && !suggestions.isEmpty()) {
                extra += 10;
            }
            extra += suggestions.size() * SUGG_ROW_H;
        }
        return HEADER_H + extra;
    }

    private int hdrNewX() {
        return this.width - HDR_RIGHT - NEW_BTN_W;
    }

    private int hdrSearchX() {
        return hdrNewX() - HDR_GAP - SEARCH_BTN_W;
    }

    private int hdrNotifX(String label) {
        return hdrSearchX() - HDR_GAP - RetroUi.chipWidth(this.font, label);
    }

    private String profileLabel() {
        AccountApi.Account a = AccountManager.INSTANCE.account();
        String handle = a == null ? "@?" : "@" + a.tag();
        return handle.length() > 14 ? handle.substring(0, 13) + "\u2026" : handle;
    }

    private int profileButtonWidth() {
        return this.font.width(profileLabel()) + 10;
    }

    private int updateChipY() {
        return HDR_Y + SEARCH_BTN_H + 4;
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
