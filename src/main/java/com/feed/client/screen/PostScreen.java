package com.feed.client.screen;

import com.feed.client.CommunityApi;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedTime;
import com.feed.client.FeedUtil;
import com.feed.client.NotificationPoller;
import com.feed.client.ReactionHelper;
import com.feed.data.Comment;
import com.feed.data.Post;
import com.feed.data.PostType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One post page inside the shell: the card, its comments and a reply line at the bottom. */
@OnlyIn(Dist.CLIENT)
public class PostScreen extends ShellScreen {

    private static final int TOP = 26;
    private static final int BTN_H = 13;
    private static final int INPUT_H = 18;
    private static final int MAX_COMMENT = 300;
    private static final int OLDER_H = 14;
    private static final int COMMENT_LINE = 10;
    private static final long LIVE_POLL_MS = 30_000L;

    private final Post post;
    private final List<Comment> comments = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private PostCard.Layout card;

    private boolean requested;
    private boolean loading;
    private boolean sending;
    private boolean livePolling;
    private long lastLivePoll;
    private Component error;
    private int errorColor = RetroUi.ERROR;

    private String nextBefore;
    private boolean loadingOlder;

    private EditBox input;
    private RetroButton solveButton;
    private RetroButton deleteButton;
    private RetroButton reportButton;

    private int scroll;
    private int contentHeight;
    /** y of the "КОММЕНТАРИИ" header inside the scrolled content. */
    private int commentsY;

    private record Row(Comment comment, List<String> lines, int y, int height, int imgW, int imgH) {}

    /** Where "← Назад" leads: the feed, or the page the post was opened from (notifications). */
    private net.minecraft.client.gui.screens.Screen back;

    public PostScreen(FeedScreen parent, Post post) {
        super(Component.translatable("feed.screen.post.title"), parent);
        this.post = post;
    }

    public PostScreen backTo(net.minecraft.client.gui.screens.Screen screen) {
        this.back = screen;
        return this;
    }

    @Override
    public void onClose() {
        if (back != null) {
            this.minecraft.setScreen(back);
        } else {
            super.onClose();
        }
    }

    @Override
    protected String navId() {
        return post.space == null || "global".equals(post.space) ? "feed" : "c:" + post.space;
    }

    private String spaceName() {
        if (post.space == null || "global".equals(post.space)) {
            return tr("feed.space.global");
        }
        CommunityApi.Community c = CommunityApi.cachedById(post.space);
        return c != null ? c.name() : null;
    }

    @Override
    protected void init() {
        NotificationPoller.markRead(post.id);
        int x = contentLeft();
        int w = contentWidth();
        Component back = Component.translatable("feed.screen.post.back");
        addRenderableWidget(new RetroButton(x, 5, this.font.width(back) + 12, BTN_H, back, b -> onClose()));

        int right = x + w;
        if (isOwn()) {
            Component del = Component.translatable("feed.screen.post.delete");
            int dw = this.font.width(del) + 12;
            right -= dw;
            deleteButton = addRenderableWidget(new RetroButton(right, 5, dw, BTN_H, del, b -> confirmDelete()));
            right -= 4;
            if (post.postType() == PostType.HELP && !post.solved) {
                Component solve = Component.translatable("feed.screen.post.solve");
                int sw = this.font.width(solve) + 12;
                right -= sw;
                solveButton = addRenderableWidget(new RetroButton(right, 5, sw, BTN_H, solve, b -> solve())
                        .color(PostType.GG.accent()));
            }
        } else if (CommunityApi.canModerate(post.space)) {
            Component del = Component.translatable("feed.screen.post.delete");
            int dw = this.font.width(del) + 12;
            right -= dw;
            deleteButton = addRenderableWidget(new RetroButton(right, 5, dw, BTN_H, del, b -> confirmDelete())
                    .color(RetroUi.ERROR));
            right -= 4;
            if (CommunityApi.serverMod() && post.uuid != null) {
                Component ban = Component.translatable("feed.mod.ban");
                int bw = this.font.width(ban) + 12;
                right -= bw;
                addRenderableWidget(new RetroButton(right, 5, bw, BTN_H, ban, b -> banAuthor(post.uuid, post.author, null))
                        .color(RetroUi.ERROR));
            }
        } else {
            Component rep = Component.translatable("feed.screen.post.report");
            int rw = this.font.width(rep) + 12;
            reportButton = addRenderableWidget(new RetroButton(right - rw, 5, rw, BTN_H, rep,
                    b -> openModal(new ReportScreen(home(), post.id, null))));
        }

        String keep = input != null ? input.getValue() : "";
        input = new EditBox(this.font, x + 6, inputTop() + 5, w - 12, this.font.lineHeight + 1,
                Component.translatable("feed.screen.post.placeholder"));
        input.setMaxLength(MAX_COMMENT);
        input.setBordered(false);
        input.setTextColor(RetroUi.FG);
        input.setTextColorUneditable(RetroUi.GRAY);
        input.setHint(Component.translatable("feed.screen.post.placeholder").withColor(RetroUi.GRAY & 0xFFFFFF));
        input.setValue(keep);
        input.setResponder(s -> error = null);
        input.visible = !post.solved;
        addRenderableWidget(input);

        relayout();
        if (!requested) {
            refreshComments();
        }
    }

    private boolean isOwn() {
        return FeedApiClient.isMine(post.uuid);
    }

    // ---------- loading ----------

    private void refreshComments() {
        requested = true;
        loading = true;
        error = null;
        lastLivePoll = System.currentTimeMillis();
        FeedApiClient.INSTANCE.fetchComments(post.id, null, true).whenComplete((page, ex) ->
                this.minecraft.execute(() -> {
                    loading = false;
                    if (ex != null) {
                        showError(FeedApiClient.unwrap(ex).userMessage());
                        return;
                    }
                    comments.clear();
                    if (page.comments() != null) {
                        comments.addAll(page.comments());
                    }
                    nextBefore = page.nextBefore();
                    post.commentCount = Math.max(comments.size(), nextBefore != null ? post.commentCount : 0);
                    relayout();
                }));
    }

    @Override
    public void tick() {
        super.tick();
        if (!requested || loading || sending || loadingOlder || livePolling || post.solved) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastLivePoll < LIVE_POLL_MS) {
            return;
        }
        lastLivePoll = now;
        livePolling = true;
        FeedApiClient.INSTANCE.fetchCommentsIfCached(post.id).whenComplete((page, ex) ->
                this.minecraft.execute(() -> {
                    livePolling = false;
                    if (ex == null && page != null && !page.notModified() && page.comments() != null) {
                        mergeNewComments(page.comments());
                    }
                }));
    }

    private void mergeNewComments(List<Comment> page) {
        Set<String> known = new HashSet<>();
        for (Comment c : comments) {
            if (c.id != null) {
                known.add(c.id);
            }
        }
        boolean added = false;
        for (Comment c : page) {
            if (c.id != null && known.add(c.id)) {
                comments.add(c);
                added = true;
            }
        }
        if (!added) {
            return;
        }
        boolean stick = isNearBottom();
        post.commentCount = Math.max(post.commentCount, comments.size());
        relayout();
        if (stick) {
            scrollToBottom();
        }
    }

    private boolean isNearBottom() {
        return scroll >= maxScroll() - SCROLL_STEP * 2;
    }

    private void loadOlder() {
        loadingOlder = true;
        FeedApiClient.INSTANCE.fetchComments(post.id, nextBefore).whenComplete((page, ex) ->
                this.minecraft.execute(() -> {
                    loadingOlder = false;
                    if (ex != null || page == null) {
                        return;
                    }
                    int before = contentHeight;
                    comments.addAll(0, page.comments());
                    nextBefore = page.nextBefore();
                    relayout();
                    scroll += contentHeight - before;
                    clampScroll();
                }));
    }

    // ---------- actions ----------

    private void send() {
        if (sending || post.solved) {
            return;
        }
        String text = input.getValue().trim();
        if (text.isEmpty()) {
            return;
        }
        sending = true;
        error = null;
        FeedApiClient.INSTANCE.createComment(post.id, text).whenComplete((comment, ex) ->
                this.minecraft.execute(() -> {
                    sending = false;
                    if (ex != null) {
                        showError(FeedApiClient.unwrap(ex).userMessage());
                        return;
                    }
                    comments.add(comment);
                    post.commentCount = Math.max(post.commentCount + 1, comments.size());
                    input.setValue("");
                    relayout();
                    scrollToBottom();
                }));
    }

    private void solve() {
        solveButton.active = false;
        FeedApiClient.INSTANCE.solve(post.id).whenComplete((res, ex) ->
                this.minecraft.execute(() -> {
                    if (ex != null) {
                        solveButton.active = true;
                        showError(FeedApiClient.unwrap(ex).userMessage());
                        return;
                    }
                    post.solved = true;
                    solveButton.visible = false;
                    input.visible = false;
                    relayout();
                }));
    }

    private void confirmDelete() {
        this.minecraft.setScreen(new RetroConfirmScreen(this,
                Component.translatable("feed.screen.post.delete_title"),
                Component.translatable("feed.screen.post.delete_confirm"),
                Component.translatable("feed.screen.post.delete"), true, () -> {
            this.minecraft.setScreen(this);
            deleteButton.active = false;
            FeedApiClient.INSTANCE.deletePost(post.id).whenComplete((v, ex) ->
                    this.minecraft.execute(() -> {
                        if (ex != null) {
                            deleteButton.active = true;
                            showError(FeedApiClient.unwrap(ex).userMessage());
                            return;
                        }
                        home().refresh();
                        onClose();
                    }));
        }));
    }

    private void openModal(ModalScreen m) {
        m.from(this);
        this.minecraft.setScreen(m);
    }

    private void banAuthor(String userId, String nick, Comment c) {
        openModal(new BanScreen(home(), userId, nick, post.id, c == null ? null : c.id, until -> {
            error = Component.translatable("feed.mod.done.banned");
            errorColor = AccountUi.GREEN;
            refreshComments();
        }));
    }

    // ---------- comment actions (shown on hover) ----------

    private record Action(String label, int color, Runnable run) {}

    private List<Action> commentActions(Comment c) {
        List<Action> out = new ArrayList<>();
        if (c.id == null) {
            return out;
        }
        boolean mine = c.authorId != null && FeedApiClient.isMine(c.authorId);
        boolean mod = CommunityApi.canModerate(post.space);
        if (mine || isOwn() || mod) {
            out.add(new Action(tr("feed.comment.delete"), RetroUi.ERROR, () -> confirmDeleteComment(c)));
        }
        if (!mine && mod && CommunityApi.serverMod() && c.authorId != null) {
            out.add(new Action(tr("feed.comment.ban"), RetroUi.ERROR, () -> banAuthor(c.authorId, commentAuthor(c), c)));
        }
        if (!mine && !mod) {
            out.add(new Action(tr("feed.comment.report"), RetroUi.GRAY, () -> openModal(new ReportScreen(home(), post.id, c.id))));
        }
        return out;
    }

    private void confirmDeleteComment(Comment c) {
        this.minecraft.setScreen(new RetroConfirmScreen(this,
                Component.translatable("feed.comment.delete_title"),
                Component.translatable("feed.comment.delete_confirm"),
                Component.translatable("feed.comment.delete"), true, () -> {
            this.minecraft.setScreen(this);
            FeedApiClient.INSTANCE.deleteComment(post.id, c.id).whenComplete((v, ex) ->
                    this.minecraft.execute(() -> {
                        if (ex != null) {
                            showError(FeedApiClient.unwrap(ex).userMessage());
                            return;
                        }
                        comments.remove(c);
                        post.commentCount = Math.max(0, post.commentCount - 1);
                        relayout();
                    }));
        }));
    }

    /** Right edge layout of the hover actions: returns the x of each action, right-aligned on the header line. */
    private int[] actionXs(List<Action> acts, int x) {
        int[] xs = new int[acts.size()];
        int ax = x + contentWidth() - 2;
        for (int i = acts.size() - 1; i >= 0; i--) {
            ax -= this.font.width(acts.get(i).label());
            xs[i] = ax;
            ax -= 8;
        }
        return xs;
    }

    private void showError(Component c) {
        error = c;
        errorColor = RetroUi.ERROR;
    }

    // ---------- layout ----------

    private int commentTextW() {
        return contentWidth() - 30;
    }

    private void relayout() {
        int w = contentWidth();
        card = PostCard.layout(this.font, post, w, this.height, true);
        int y = card.h() + 10;
        commentsY = y;
        y += 13;
        if (nextBefore != null) {
            y += OLDER_H;
        }
        rows.clear();
        for (Comment c : comments) {
            List<String> lines = RetroUi.wrap(this.font, c.text != null ? c.text : "", commentTextW());
            int iw = 0;
            int ih = 0;
            int h = 10 + lines.size() * COMMENT_LINE;
            if (c.imageUrl != null) {
                int[] s = ImageBox.fit(c.imageW, c.imageH, Math.min(200, commentTextW()), 90);
                iw = s[0];
                ih = s[1];
                h += ih + 4;
            }
            h = Math.max(h, 18);
            rows.add(new Row(c, lines, y, h, iw, ih));
            y += h + 6;
        }
        if (comments.isEmpty()) {
            y += 14;
        }
        contentHeight = y;
        clampScroll();
    }

    private int inputTop() {
        return this.height - 24;
    }

    private int listBottom() {
        return inputTop() - 6;
    }

    private int maxScroll() {
        return Math.max(0, contentHeight - (listBottom() - TOP));
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
    }

    private void scrollToBottom() {
        scroll = maxScroll();
    }

    // ---------- rendering ----------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = contentLeft();
        int w = contentWidth();
        int y = inputTop();
        if (post.solved) {
            RetroUi.drawBox(g, x, y, w, INPUT_H, false);
            g.drawString(this.font, tr("feed.screen.post.closed"), x + 6, y + 5, RetroUi.GRAY, false);
        } else {
            boolean focused = input != null && input.isFocused();
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + INPUT_H;
            RetroUi.drawBox(g, x, y, w, INPUT_H, hover && !focused);
            if (focused) {
                g.renderOutline(x, y, w, INPUT_H, RetroUi.ACCENT);
            }
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = contentLeft();
        int w = contentWidth();
        Component back = Component.translatable("feed.screen.post.back");
        g.drawString(this.font, this.title, x + this.font.width(back) + 20, 8, RetroUi.FG, false);

        int top = TOP;
        int bottom = listBottom();
        boolean inList = mouseY >= top && mouseY < bottom;
        g.enableScissor(sidebarW(), top, this.width, bottom);
        int y0 = top - scroll;
        boolean cardHover = false;
        PostCard.draw(g, this.font, card, x, y0, cardHover, inList, mouseX, mouseY, spaceName());

        int hy = y0 + commentsY;
        g.drawString(this.font, Component.translatable("feed.screen.post.comments", post.commentCount), x, hy, RetroUi.GRAY, false);
        if (nextBefore != null) {
            int oy = hy + 13;
            String label = loadingOlder ? "..." : tr("feed.screen.post.load_older");
            boolean hover = inList && mouseY >= oy - 2 && mouseY < oy + 10 && mouseX >= x && mouseX < x + this.font.width(label) + 8;
            g.drawString(this.font, label, x + 4, oy, hover ? RetroUi.ACCENT : RetroUi.GRAY, false);
        }
        if (comments.isEmpty() && !loading) {
            g.drawString(this.font, tr("feed.screen.post.empty"), x + 6, hy + 13, RetroUi.GRAY, false);
        }
        for (Row row : rows) {
            int cy = y0 + row.y();
            if (cy + row.height() >= top && cy <= bottom) {
                drawComment(g, row, x, cy, inList ? mouseX : -1, inList ? mouseY : -1);
            }
        }
        if (loading) {
            String load = tr("feed.screen.loading") + (RetroUi.blinkFast() ? "_" : " ");
            g.drawString(this.font, load, x + 6, hy + 13, RetroUi.GRAY, false);
        }
        g.disableScissor();
        drawScrollbar(g, top, bottom, scroll, contentHeight);

        if (error != null) {
            String e = FeedUtil.clip(this.font, error.getString(), w - 12);
            int ew = this.font.width(e) + 10;
            int ey = bottom - 14;
            g.fill(x + w - ew, ey, x + w, ey + 12, RetroUi.BG);
            g.renderOutline(x + w - ew, ey, ew, 12, errorColor);
            g.drawString(this.font, e, x + w - ew + 5, ey + 2, errorColor, false);
        }
    }

    private String commentAuthor(Comment c) {
        return c.author == null || c.author.isEmpty() ? "?" : c.author;
    }

    private void drawComment(GuiGraphics g, Row row, int x, int y, int mouseX, int mouseY) {
        Comment c = row.comment();
        String name = commentAuthor(c);
        RetroUi.drawAvatar(g, this.font, FeedApiClient.avatarKey(c.authorId, c.avatar), name, x + 4, y, 16);
        boolean byAuthor = c.authorId != null && c.authorId.equals(post.uuid);
        boolean nameHover = c.fromMod && c.authorId != null && mouseY >= y && mouseY < y + 9
                && mouseX >= x + 4 && mouseX < x + 25 + this.font.width(name);
        int nx = x + 25;
        String shown = FeedUtil.clip(this.font, name, contentWidth() / 2);
        g.drawString(this.font, shown, nx, y, byAuthor ? RetroUi.ACCENT : nameHover ? RetroUi.STRONG : RetroUi.FG, false);
        nx += this.font.width(shown);
        String meta = (c.fromMod ? (c.authorTag != null ? " @" + c.authorTag : "") : " \u00b7 " + tr("feed.author.discord"))
                + " \u00b7 " + FeedTime.ago(c.createdAt);
        g.drawString(this.font, FeedUtil.clip(this.font, meta, Math.max(10, x + contentWidth() - nx)), nx, y, RetroUi.GRAY, false);
        int ly = y + 10;
        for (String line : row.lines()) {
            PostCard.drawRich(g, this.font, line, x + 25, ly, RetroUi.FG, mouseX, mouseY);
            ly += COMMENT_LINE;
        }
        if (c.imageUrl != null) {
            ImageBox.draw(g, this.font, c.imageUrl, x + 25, ly + 2, row.imgW(), row.imgH());
        }
        if (mouseX >= x && mouseX < x + contentWidth() && mouseY >= y - 3 && mouseY < y + row.height() + 3) {
            List<Action> acts = commentActions(c);
            if (!acts.isEmpty()) {
                int[] xs = actionXs(acts, x);
                g.fill(xs[0] - 6, y - 1, x + contentWidth(), y + 9, RetroUi.PAGE);
                for (int i = 0; i < acts.size(); i++) {
                    Action a = acts.get(i);
                    int aw = this.font.width(a.label());
                    boolean hover = mouseY >= y - 1 && mouseY < y + 9 && mouseX >= xs[i] - 2 && mouseX < xs[i] + aw + 2;
                    g.drawString(this.font, a.label(), xs[i], y, hover ? RetroUi.STRONG : a.color(), false);
                    if (hover) {
                        g.fill(xs[i], y + 9, xs[i] + aw, y + 10, RetroUi.STRONG);
                    }
                }
            }
        }
    }

    // ---------- input ----------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        scroll -= (int) (dy * SCROLL_STEP);
        clampScroll();
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
        if (!post.solved && mouseY >= inputTop() && mouseY < inputTop() + INPUT_H && mouseX >= x && mouseX < x + w) {
            setFocused(input);
            input.setFocused(true);
            return true;
        }
        int top = TOP;
        int bottom = listBottom();
        if (mouseY < top || mouseY >= bottom || mouseX < x || mouseX >= x + w) {
            return false;
        }
        int y0 = top - scroll;
        if (mouseY < y0 + card.h()) {
            PostCard.Hit hit = PostCard.hit(this.font, card, x, y0, mouseX, mouseY);
            switch (hit.kind()) {
                case AUTHOR, MENTION -> openPlayer(hit.value());
                case HASHTAG -> openHashtag(post.space, hit.value());
                case COORDS -> com.feed.client.PostActions.copyCoords(hit.value());
                case POLL -> com.feed.client.PostActions.vote(post, Integer.parseInt(hit.value()), this::relayout);
                case REACTION -> ReactionHelper.toggle(post, hit.value(), null);
                case IMAGE -> this.minecraft.setScreen(new ImageViewScreen(this, post.imageUrl, post.imageW, post.imageH));
                default -> {
                }
            }
            return true;
        }
        int oy = y0 + commentsY + 13;
        if (nextBefore != null && !loadingOlder && mouseY >= oy - 2 && mouseY < oy + 10) {
            loadOlder();
            return true;
        }
        for (Row row : rows) {
            int cy = y0 + row.y();
            if (mouseY < cy || mouseY >= cy + row.height()) {
                continue;
            }
            Comment c = row.comment();
            if (mouseY >= cy - 1 && mouseY < cy + 9) {
                List<Action> acts = commentActions(c);
                int[] xs = actionXs(acts, x);
                for (int i = 0; i < acts.size(); i++) {
                    if (mouseX >= xs[i] - 2 && mouseX < xs[i] + this.font.width(acts.get(i).label()) + 2) {
                        acts.get(i).run().run();
                        return true;
                    }
                }
            }
            if (c.fromMod && c.authorId != null && mouseY < cy + 9
                    && mouseX >= x + 4 && mouseX < x + 25 + this.font.width(commentAuthor(c))) {
                openPlayer(c.authorId);
                return true;
            }
            int ly = cy + 10;
            if (mouseY >= ly && mouseY < ly + row.lines().size() * COMMENT_LINE) {
                String link = PostCard.linkAt(this.font, row.lines().get((int) ((mouseY - ly) / COMMENT_LINE)), x + 25, mouseX);
                if (link != null) {
                    if (link.startsWith("~")) {
                        com.feed.client.PostActions.copyCoords(link.substring(1));
                    } else if (link.startsWith("#")) {
                        openHashtag(post.space, link.substring(1));
                    } else {
                        openPlayer(link.substring(1));
                    }
                    return true;
                }
            }
            if (c.imageUrl != null) {
                int iy = ly + row.lines().size() * COMMENT_LINE + 2;
                if (mouseY >= iy && mouseY < iy + row.imgH() && mouseX >= x + 25 && mouseX < x + 25 + row.imgW()) {
                    this.minecraft.setScreen(new ImageViewScreen(this, c.imageUrl, c.imageW, c.imageH));
                    return true;
                }
            }
            break;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && input.visible && input.isFocused()) {
            send();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
