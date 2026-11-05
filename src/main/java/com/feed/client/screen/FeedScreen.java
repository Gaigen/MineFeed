package com.feed.client.screen;

import com.feed.client.CommunityApi;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedUtil;
import com.feed.client.ReactionHelper;
import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import com.feed.client.account.UpdateChecker;
import com.feed.data.Post;
import com.feed.data.PostType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
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
public class FeedScreen extends ShellScreen {

    private static final int PAGE_SIZE = 20;
    private static final int CARD_GAP = 8;
    private static final int TAB_GAP = 3;
    private static final int LOAD_MORE_THRESHOLD = 40;
    private static final int TABS_Y = 22;
    private static final int COMPOSE_Y = 40;
    private static final int COMPOSE_H = 22;
    private static final int PROFILE_CARD_Y = 22;
    private static final int PROFILE_CARD_H = 52;
    private static final int BTN_H = 13;
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
    private RetroButton manageButton;
    private RetroButton backButton;
    /** Open player profile (account id or tag); null = the normal feed of {@link #space}. */
    private String profileKey;
    private FeedApiClient.Profile profile;
    /** Normalized #hashtag filter of the feed (without "#"); null = all posts. */
    private String hashtag;

    private RetroButton settingsButton;
    private RetroButton banButton;

    private record Row(Post post, PostCard.Layout layout, int y) {
        int height() {
            return layout.h();
        }
    }

    private record Tab(String label, int color, boolean active, PostType type, boolean mine) {}

    public FeedScreen() {
        super(Component.translatable("feed.brand.title"), null);
    }

    /** Opens the global feed filtered by #tag. */
    public FeedScreen(String tag) {
        this();
        this.hashtag = TagText.normalizeHashtag(tag);
    }

    public String hashtag() {
        return hashtag;
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

        manageButton = addRenderableWidget(new RetroButton(0, 5, 70, BTN_H,
                Component.translatable("feed.space.manage"), b -> {
            CommunityApi.Community c = community();
            if (c != null) {
                this.minecraft.setScreen(new CommunityScreen(this, c));
            }
        }));
        Component back = Component.translatable("feed.profile.back");
        int bw = this.font.width(back) + 12;
        backButton = addRenderableWidget(new RetroButton(contentLeft() + contentWidth() - bw, 5, bw, BTN_H, back,
                b -> back()));
        Component settings = Component.translatable("feed.profile.settings");
        int sw = this.font.width(settings) + 12;
        settingsButton = addRenderableWidget(new RetroButton(contentLeft() + contentWidth() - sw - 6, PROFILE_CARD_Y + 6,
                sw, BTN_H, settings, b -> this.minecraft.setScreen(new ProfileScreen(this))));
        banButton = addRenderableWidget(new RetroButton(0, PROFILE_CARD_Y + 6, 60, BTN_H, Component.empty(), b -> toggleBan())
                .color(RetroUi.ERROR));
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
        manageButton.visible = c != null && profileKey == null && hashtag == null;
        if (backButton != null) {
            backButton.visible = profileKey != null ? !ownProfile() : hashtag != null;
        }
        if (settingsButton != null) {
            settingsButton.visible = profileKey != null && ownProfile();
        }
        if (banButton != null) {
            banButton.visible = profile != null && profileKey != null && !ownProfile() && CommunityApi.serverMod()
                    && !profile.moderator();
            if (banButton.visible) {
                Component label = Component.translatable(profile.banned() ? "feed.profile.unban" : "feed.profile.ban");
                int w = this.font.width(label) + 12;
                banButton.setMessage(label);
                banButton.setWidth(w);
                banButton.setX(contentLeft() + contentWidth() - w - 6);
            }
        }
        if (c != null) {
            Component label = Component.translatable(c.isMod() ? "feed.space.manage" : "feed.space.info");
            int w = this.font.width(label) + 12;
            manageButton.setMessage(label);
            manageButton.setWidth(w);
            manageButton.setX(contentLeft() + contentWidth() - w);
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
        if (loading || livePolling || posts.isEmpty() || profileKey != null) {
            return;
        }
        if (now - lastLivePoll < LIVE_POLL_MS) {
            return;
        }
        lastLivePoll = now;
        pollLiveFeed();
    }

    // ---------- player profiles ----------

    private boolean ownProfile() {
        return profile != null && FeedApiClient.isMine(profile.id());
    }

    /** Shows a player's card and their posts in the global feed (by account id or @tag). */
    public void openProfile(String key) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if (profile != null && (key.equals(profile.id()) || key.equalsIgnoreCase(profile.tag()))) {
            scroll = 0;
            return;
        }
        profileKey = key;
        profile = null;
        posts.clear();
        nextCursor = null;
        scroll = 0;
        pendingNewAbove = 0;
        relayout();
        updateManageButton();
        loading = true;
        error = null;
        FeedApiClient.INSTANCE.fetchProfile(key).whenComplete((p, ex) -> this.minecraft.execute(() -> {
            if (!key.equals(profileKey)) {
                return;
            }
            if (ex != null) {
                loading = false;
                error = FeedApiClient.unwrap(ex).userMessage();
                return;
            }
            profile = p;
            updateManageButton();
            requestPage(null);
        }));
    }

    private void reloadProfile() {
        if (profile == null) {
            return;
        }
        String key = profile.id();
        profile = null;
        profileKey = null;
        openProfile(key);
    }

    private void toggleBan() {
        FeedApiClient.Profile p = profile;
        if (p == null) {
            return;
        }
        if (p.banned()) {
            this.minecraft.setScreen(new RetroConfirmScreen(this, Component.translatable("feed.profile.unban"),
                    Component.translatable("feed.profile.unban_confirm", p.nick()), Component.translatable("feed.profile.unban"),
                    false, () -> {
                this.minecraft.setScreen(this);
                com.feed.client.ModerationApi.unban(p.id()).whenComplete((v, ex) -> this.minecraft.execute(() -> {
                    if (ex != null) {
                        error = com.feed.client.ModerationApi.describe(ex);
                        return;
                    }
                    reloadProfile();
                }));
            }));
            return;
        }
        this.minecraft.setScreen(new BanScreen(this, p.id(), p.nick(), null, null, until -> reloadProfile()));
    }

    public void closeProfile() {
        profileKey = null;
        profile = null;
        updateManageButton();
        refresh();
    }

    private void back() {
        if (profileKey != null) {
            closeProfile();
        } else if (hashtag != null) {
            hashtag = null;
            updateManageButton();
            refresh();
        }
    }

    /** Posts of the space with #tag; the "back" button returns to the whole feed. */
    public void openHashtag(String newSpace, String tag) {
        String t = TagText.normalizeHashtag(tag);
        if (t == null) {
            return;
        }
        profileKey = null;
        profile = null;
        space = newSpace == null ? "global" : newSpace;
        hashtag = t;
        filter = null;
        mineOnly = false;
        updateManageButton();
        refresh();
    }

    public void switchSpace(String newSpace) {
        profileKey = null;
        profile = null;
        hashtag = null;
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
        if (profileKey != null && profile == null) {
            String key = profileKey;
            profileKey = null;
            openProfile(key);
            return;
        }
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
        if (profileKey != null && profile != null) {
            String forProfile = profileKey;
            FeedApiClient.INSTANCE.fetchAuthorFeed(profile.id(), cursor, PAGE_SIZE)
                    .whenComplete((page, ex) -> this.minecraft.execute(() -> {
                        if (!forProfile.equals(profileKey)) {
                            return;
                        }
                        loading = false;
                        if (ex != null) {
                            error = FeedApiClient.unwrap(ex).userMessage();
                            return;
                        }
                        if (page.body() != null && page.body().posts != null) {
                            posts.addAll(page.body().posts);
                            nextCursor = page.body().nextCursor;
                        }
                        relayout();
                    }));
            return;
        }
        String forSpace = space;
        String forTag = hashtag;
        FeedApiClient.INSTANCE.fetchFeed(forSpace, forTag, cursor, PAGE_SIZE, mineOnly, false)
                .whenComplete((page, ex) -> this.minecraft.execute(() -> {
                    if (!forSpace.equals(space) || !Objects.equals(forTag, hashtag) || profileKey != null) {
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
        String forTag = hashtag;
        FeedApiClient.INSTANCE.fetchFeed(forSpace, forTag, null, PAGE_SIZE, mineOnly, true)
                .whenComplete((page, ex) -> this.minecraft.execute(() -> {
                    livePolling = false;
                    if (!forSpace.equals(space) || !Objects.equals(forTag, hashtag) || profileKey != null
                            || ex != null || page == null || page.notModified()) {
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
        int y = 0;
        for (Post post : posts) {
            if (filter != null && post.postType() != filter) {
                continue;
            }
            PostCard.Layout l = PostCard.layout(this.font, post, contentWidth(), this.height, false);
            rows.add(new Row(post, l, y));
            y += l.h() + CARD_GAP;
        }
        if (!rows.isEmpty() && nextCursor == null && !loading) {
            y += 14; // "that's all"
        }
        contentHeight = y;
        clampScroll();
    }

    // ---------- geometry ----------

    @Override
    protected String navId() {
        if (profileKey != null) {
            return ownProfile() || (profile == null && profileKey.equals(ownId())) ? "profile" : null;
        }
        return "global".equals(space) ? "feed" : "c:" + space;
    }

    private int listTop() {
        return profileKey != null ? PROFILE_CARD_Y + PROFILE_CARD_H + 8 : COMPOSE_Y + COMPOSE_H + 8;
    }

    private int listBottom() {
        return this.height - 4;
    }

    // ---------- rendering ----------

    /** The profile card frame goes under the widgets (the settings button sits on it). */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        if (profileKey != null) {
            RetroUi.drawBox(g, contentLeft(), PROFILE_CARD_Y, contentWidth(), PROFILE_CARD_H, false);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderHeader(g, mouseX, mouseY);
        renderList(g, mouseX, mouseY);
    }

    private void renderProfileHeader(GuiGraphics g, int mouseX, int mouseY) {
        int x = contentLeft();
        int w = contentWidth();
        boolean own = ownProfile();
        int right = backButton != null && backButton.visible ? backButton.getX() - 6 : x + w;
        drawTitle(g, own ? tr("feed.nav.profile") : tr("feed.profile.player"), null, x, TITLE_Y, right);
        int y = PROFILE_CARD_Y;
        if (profile == null) {
            g.drawString(this.font, tr("feed.screen.loading") + (RetroUi.blinkFast() ? "_" : ""), x + 8, y + 8, RetroUi.GRAY, false);
            return;
        }
        int size = 40;
        g.fill(x + 8, y + 8, x + 8 + size, y + 8 + size, RetroUi.SHADOW);
        RetroUi.drawAvatar(g, this.font, FeedApiClient.avatarKey(profile.id(), profile.avatar()), profile.nick(), x + 6, y + 6, size);
        g.renderOutline(x + 6, y + 6, size, size, RetroUi.GRAY);
        int tx = x + 54;
        int nameRight = settingsButton != null && settingsButton.visible ? settingsButton.getX() - 6
                : banButton != null && banButton.visible ? banButton.getX() - 6 : x + w - 6;
        String nick = FeedUtil.clip(this.font, profile.nick(), Math.max(10, (nameRight - tx) / 2));
        g.pose().pushPose();
        g.pose().translate(tx, y + 8, 0);
        g.pose().scale(2f, 2f, 1f);
        g.drawString(this.font, nick, 0, 0, own ? RetroUi.ACCENT : RetroUi.FG, false);
        g.pose().popPose();
        String handle = "@" + profile.tag();
        g.drawString(this.font, handle, tx, y + 28, RetroUi.ACCENT, false);
        if (profile.discord()) {
            String prot = " " + tr("feed.profile.protected");
            int px = tx + this.font.width(handle);
            if (px + this.font.width(prot) < x + w - 6) {
                g.drawString(this.font, prot, px, y + 28, AccountUi.GREEN, false);
            }
        }
        List<String> parts = new ArrayList<>();
        parts.add(tr("feed.profile.posts", profile.posts()));
        parts.add(tr("feed.profile.reactions", profile.reactions()));
        if (profile.solved() > 0) {
            parts.add(tr("feed.profile.solved", profile.solved()));
        }
        parts.add(Component.translatable("feed.account.profile.since", AccountUi.date(profile.createdAt())).getString());
        String stats = String.join(" \u00b7 ", parts);
        g.drawString(this.font, FeedUtil.clip(this.font, stats, x + w - 6 - tx), tx, y + 39, RetroUi.GRAY, false);
        if (profile.banned()) {
            String ban = profile.banUntil() == null ? tr("feed.profile.banned_forever")
                    : tr("feed.profile.banned_until", AccountUi.date(profile.banUntil()));
            int cw = RetroUi.chipWidth(this.font, ban);
            RetroUi.drawChip(g, this.font, ban, x + w - 6 - cw, y + 23, RetroUi.ERROR, true);
        }
        if (profile.moderator()) {
            String mod = tr("feed.profile.mod");
            int cw = RetroUi.chipWidth(this.font, mod);
            if (settingsButton == null || !settingsButton.visible) {
                RetroUi.drawChip(g, this.font, mod, x + w - 6 - cw, y + 6, RetroUi.CYAN, false);
            }
        }
    }

    private void renderHeader(GuiGraphics g, int mouseX, int mouseY) {
        if (profileKey != null) {
            renderProfileHeader(g, mouseX, mouseY);
            return;
        }
        int x = contentLeft();
        int w = contentWidth();
        CommunityApi.Community c = community();
        int right = manageButton != null && manageButton.visible ? manageButton.getX() - 6
                : backButton != null && backButton.visible ? backButton.getX() - 6 : x + w;
        if (hashtag != null) {
            String where = c == null ? tr("feed.space.global") : c.name();
            g.drawString(this.font, "#", x, TITLE_Y, RetroUi.CYAN, false);
            drawTitle(g, hashtag, Component.translatable("feed.hashtag.sub", where).getString(), x + this.font.width("#"), TITLE_Y, right);
        } else if (c == null) {
            drawTitle(g, tr("feed.space.global"), tr("feed.space.global_sub"), x, TITLE_Y, right);
        } else {
            PixelIcon.draw(g, c.isOwner() ? "crown" : "lock", x, TITLE_Y, RetroUi.ACCENT);
            drawTitle(g, c.name(), Component.translatable("feed.space.members", c.members()).getString()
                    + " \u00b7 " + tr("feed.space.closed"), x + 10, TITLE_Y, right);
        }

        int tx = x;
        for (Tab tab : tabs()) {
            int cw = RetroUi.chipWidth(this.font, tab.label());
            boolean hover = !tab.active() && mouseX >= tx && mouseX < tx + cw && mouseY >= TABS_Y && mouseY < TABS_Y + RetroUi.CHIP_H;
            RetroUi.drawChip(g, this.font, tab.label(), tx, TABS_Y, hover ? RetroUi.FG : tab.color(), tab.active());
            tx += cw + TAB_GAP;
        }

        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= COMPOSE_Y && mouseY < COMPOSE_Y + COMPOSE_H;
        RetroUi.drawBox(g, x, COMPOSE_Y, w, COMPOSE_H, hover);
        AccountApi.Account a = AccountManager.INSTANCE.account();
        String nick = a == null ? "?" : a.nick();
        RetroUi.drawAvatar(g, this.font, FeedApiClient.avatarKey(a == null ? null : a.id(), null), nick, x + 4, COMPOSE_Y + 3, 16);
        String hint = hashtag != null ? Component.translatable("feed.hashtag.compose", "#" + hashtag).getString()
                : Component.translatable("feed.compose.bar", nick).getString();
        g.drawString(this.font, FeedUtil.clip(this.font, hint, w - 44), x + 25, COMPOSE_Y + 8, hover ? RetroUi.FG : RetroUi.GRAY, false);
        PixelIcon.draw(g, "img", x + w - 13, COMPOSE_Y + 8, hover ? RetroUi.FG : RetroUi.GRAY);
    }

    private List<Tab> tabs() {
        List<Tab> list = new ArrayList<>();
        list.add(new Tab(tr("feed.screen.feed.tab.all"), RetroUi.FG, filter == null && !mineOnly, null, false));
        for (PostType type : PostType.values()) {
            list.add(new Tab(type.prefix, type.accent(), filter == type, type, false));
        }
        list.add(new Tab(tr("feed.screen.feed.tab.mine"), RetroUi.ACCENT, mineOnly, null, true));
        return list;
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        int top = listTop();
        int bottom = listBottom();
        int cx = contentLeft();
        int w = contentWidth();
        int mid = cx + w / 2;
        g.enableScissor(sidebarW(), top, this.width, bottom);
        boolean inList = mouseY >= top && mouseY < bottom;

        int endY = top - scroll;
        for (Row row : rows) {
            int y = top + row.y() - scroll;
            endY = y + row.height() + CARD_GAP;
            if (y + row.height() + RetroUi.SHADOW_OFFSET >= top && y <= bottom) {
                boolean hovered = inList && mouseX >= cx && mouseX < cx + w && mouseY >= y && mouseY < y + row.height();
                PostCard.draw(g, this.font, row.layout(), cx, y, hovered, inList, mouseX, mouseY, null);
            }
        }
        if (!rows.isEmpty() && nextCursor == null && !loading) {
            RetroUi.centered(g, this.font, tr("feed.screen.feed.end"), mid, endY + 2, RetroUi.GRAY);
        }

        if (rows.isEmpty() && !loading && error == null) {
            String key;
            if (profileKey != null) {
                key = profile == null ? null : "feed.profile.empty";
            } else if (mineOnly) {
                key = "feed.screen.feed.empty.mine";
            } else if (hashtag != null && filter == null) {
                key = null;
                RetroUi.centered(g, this.font, Component.translatable("feed.hashtag.empty", "#" + hashtag), mid, top + 12, RetroUi.GRAY);
            } else if (filter != null) {
                key = filter.emptyKey();
            } else if (!"global".equals(space)) {
                key = "feed.screen.feed.empty.community";
            } else {
                key = "feed.screen.feed.empty";
            }
            if (key != null) {
                RetroUi.centered(g, this.font, Component.translatable(key), mid, top + 12, RetroUi.GRAY);
            }
        }
        if (loading) {
            String load = tr("feed.screen.loading") + (RetroUi.blinkFast() ? "_" : " ");
            RetroUi.centered(g, this.font, load, mid, bottom - 12, RetroUi.GRAY);
        }
        if (error != null) {
            RetroUi.centered(g, this.font, error, mid, bottom - 12, RetroUi.ERROR);
        }
        g.disableScissor();

        if (pendingNewAbove > 0) {
            String label = newAboveLabel();
            int lw = RetroUi.chipWidth(this.font, label);
            int lx = mid - lw / 2;
            boolean hover = mouseX >= lx && mouseX < lx + lw && mouseY >= top + 2 && mouseY < top + 2 + RetroUi.CHIP_H;
            RetroUi.drawChip(g, this.font, label, lx, top + 2, RetroUi.ACCENT, !hover);
        }
        drawScrollbar(g, top, bottom, scroll, contentHeight);
    }

    private String newAboveLabel() {
        return Component.translatable("feed.screen.feed.new_above", pendingNewAbove).getString();
    }

    // ---------- input ----------

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
        int viewport = listBottom() - listTop();
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
        int x = contentLeft();
        int w = contentWidth();
        if (profileKey == null) {
            if (mouseY >= TABS_Y && mouseY < TABS_Y + RetroUi.CHIP_H) {
                int tx = x;
                for (Tab tab : tabs()) {
                    int cw = RetroUi.chipWidth(this.font, tab.label());
                    if (mouseX >= tx && mouseX < tx + cw) {
                        if (tab.mine()) {
                            mineOnly = !mineOnly;
                            filter = null;
                            refresh();
                        } else {
                            setFilter(tab.type());
                        }
                        return true;
                    }
                    tx += cw + TAB_GAP;
                }
            }
            if (mouseY >= COMPOSE_Y && mouseY < COMPOSE_Y + COMPOSE_H && mouseX >= x && mouseX < x + w) {
                this.minecraft.setScreen(hashtag != null
                        ? new ComposeScreen(this, filter != null ? filter : PostType.TALK, "#" + hashtag + " ")
                        : new ComposeScreen(this));
                return true;
            }
        }

        int top = listTop();
        if (pendingNewAbove > 0) {
            int lw = RetroUi.chipWidth(this.font, newAboveLabel());
            int lx = x + w / 2 - lw / 2;
            if (mouseX >= lx && mouseX < lx + lw && mouseY >= top + 2 && mouseY < top + 2 + RetroUi.CHIP_H) {
                scroll = 0;
                pendingNewAbove = 0;
                return true;
            }
        }
        if (mouseY >= top && mouseY < listBottom() && mouseX >= x && mouseX < x + w) {
            for (Row row : rows) {
                int y = top + row.y() - scroll;
                if (mouseY >= y && mouseY < y + row.height()) {
                    return clickInsideCard(row, x, y, mouseX, mouseY);
                }
            }
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
        PostCard.Hit hit = PostCard.hit(this.font, row.layout(), x, y, mouseX, mouseY);
        switch (hit.kind()) {
            case AUTHOR, MENTION -> openProfile(hit.value());
            case HASHTAG -> openHashtag(space, hit.value());
            case COORDS -> com.feed.client.PostActions.copyCoords(hit.value());
            case POLL -> com.feed.client.PostActions.vote(post, Integer.parseInt(hit.value()), this::relayout);
            case REACTION -> ReactionHelper.toggle(post, hit.value(), null);
            case IMAGE -> this.minecraft.setScreen(new ImageViewScreen(this, post.imageUrl, post.imageW, post.imageH));
            default -> this.minecraft.setScreen(new PostScreen(this, post));
        }
        return true;
    }

    private void clampScroll() {
        int viewport = listBottom() - listTop();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewport)));
    }
}
