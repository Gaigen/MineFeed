package com.feed.client.screen;

import com.feed.client.AvatarCache;
import com.feed.client.FeedApiClient;
import com.feed.client.FeedTime;
import com.feed.client.NotificationPoller;
import com.feed.client.ReactionHelper;
import com.feed.data.Comment;
import com.feed.data.Post;
import com.feed.data.PostType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Single post with comment thread and reply box. */
@OnlyIn(Dist.CLIENT)
public class PostScreen extends RetroScreen {

    private static final int TOP = 30;
    private static final int PAD = 8;
    private static final int CARD_GAP = 10;
    private static final int INPUT_H = 20;
    private static final int SCROLL_STEP = 18;
    private static final int MAX_COMMENT = 300;
    private static final int LOAD_OLDER_H = 14;
    private static final long LIVE_POLL_MS = 30_000L;

    private final FeedScreen parent;
    private final Post post;

    private final List<Comment> comments = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private List<String> postLines = List.of();
    private int postHeight;

    private boolean requested;
    private boolean loading;
    private boolean sending;
    private boolean livePolling;
    private long lastLivePoll;
    private Component error;

    private String nextBefore = null;
    private boolean loadingOlder = false;
    private int loadOlderY = Integer.MIN_VALUE;

    private EditBox input;
    private RetroButton sendButton;
    private RetroButton solveButton;
    private RetroButton deleteButton;
    private RetroButton reportButton;

    private int scroll;
    private int contentHeight;

    private record Row(Comment comment, List<String> lines, int y, int height) {}

    public PostScreen(FeedScreen parent, Post post) {
        super(Component.translatable("feed.screen.post.title"));
        this.parent = parent;
        this.post = post;
    }

    @Override
    protected void init() {
        NotificationPoller.markRead(post.id);

        addRenderableWidget(new RetroButton(8, 6, 56, 18,
                Component.translatable("feed.screen.post.back"), b -> onClose()));

        if (post.postType() == PostType.HELP && isOwn() && !post.solved) {
            solveButton = addRenderableWidget(new RetroButton(this.width - 110, 6, 102, 18,
                    Component.translatable("feed.screen.post.solve"), b -> solve()));
        }

        if (isOwn()) {
            deleteButton = addRenderableWidget(new RetroButton(this.width - 168, 6, 52, 18,
                    Component.translatable("feed.screen.post.delete"), b -> deletePost()));
        } else {
            reportButton = addRenderableWidget(new RetroButton(this.width - 184, 6, 68, 18,
                    Component.translatable("feed.screen.post.report"), b -> reportPost()));
        }

        int x = contentLeft();
        int w = contentWidth();
        input = new EditBox(this.font, x + 5, inputTop() + 6, w - 74, this.font.lineHeight + 2,
                Component.translatable("feed.screen.post.placeholder"));
        input.setMaxLength(MAX_COMMENT);
        input.setBordered(false);
        input.setTextColor(RetroUi.FG);
        input.setTextColorUneditable(RetroUi.GRAY);
        input.setHint(Component.translatable("feed.screen.post.placeholder").withColor(RetroUi.GRAY & 0xFFFFFF));
        input.setResponder(s -> error = null);
        addRenderableWidget(input);
        sendButton = addRenderableWidget(new RetroButton(x + w - 62, inputTop(), 62, INPUT_H,
                Component.translatable("feed.screen.post.send"), b -> send()));
        applySolvedState();

        relayout();
        if (!requested) {
            refreshComments();
        }
    }

    private boolean isOwn() {
        return FeedApiClient.isMine(post.uuid);
    }

    private void applySolvedState() {
        boolean closed = post.solved;
        input.visible = !closed;
        sendButton.visible = !closed;
        if (closed && solveButton != null) {
            solveButton.visible = false;
        }
    }

    private void refreshComments() {
        requested = true;
        loading = true;
        error = null;
        lastLivePoll = System.currentTimeMillis();
        FeedApiClient.INSTANCE.fetchComments(post.id, null, true).whenComplete((page, ex) ->
                this.minecraft.execute(() -> {
                    loading = false;
                    if (ex != null) {
                        error = FeedApiClient.unwrap(ex).userMessage();
                        return;
                    }
                    comments.clear();
                    if (page.comments() != null) {
                        comments.addAll(page.comments());
                    }
                    nextBefore = page.nextBefore();
                    post.commentCount = comments.size();
                    relayout();
                    scroll = Integer.MAX_VALUE;
                    clampScroll();
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
        pollLiveComments();
    }

    private void pollLiveComments() {
        livePolling = true;
        FeedApiClient.INSTANCE.fetchCommentsIfCached(post.id).whenComplete((page, ex) ->
                this.minecraft.execute(() -> {
                    livePolling = false;
                    if (ex != null || page == null || page.notModified()) {
                        return;
                    }
                    if (page.comments() == null) {
                        return;
                    }
                    mergeNewComments(page.comments());
                }));
    }

    private void mergeNewComments(List<Comment> page) {
        Set<String> known = new HashSet<>();
        for (Comment c : comments) {
            if (c.id != null) {
                known.add(c.id);
            }
        }
        List<Comment> added = new ArrayList<>();
        for (Comment c : page) {
            if (c.id != null && known.add(c.id)) {
                added.add(c);
            }
        }
        if (added.isEmpty()) {
            return;
        }
        boolean stickBottom = isNearBottom();
        comments.addAll(added);
        post.commentCount = Math.max(post.commentCount, comments.size());
        relayout();
        if (stickBottom) {
            scroll = Integer.MAX_VALUE;
            clampScroll();
        }
    }

    private boolean isNearBottom() {
        int viewport = Math.max(1, listBottom() - TOP);
        int max = Math.max(0, contentHeight - viewport);
        return scroll >= max - SCROLL_STEP * 2;
    }

    private void loadOlder() {
        loadingOlder = true;
        FeedApiClient.INSTANCE.fetchComments(post.id, nextBefore).whenComplete((page, ex) ->
                this.minecraft.execute(() -> {
                    loadingOlder = false;
                    if (ex != null || page == null) {
                        return;
                    }
                    int added = heightOf(page.comments());
                    comments.addAll(0, page.comments());
                    nextBefore = page.nextBefore();
                    relayout();
                    scroll += added;
                    clampScroll();
                }));
    }

    private void send() {
        if (sending || post.solved) {
            return;
        }
        String text = input.getValue().trim();
        if (text.isEmpty()) {
            return;
        }
        sending = true;
        sendButton.active = false;
        error = null;
        FeedApiClient.INSTANCE.createComment(post.id, text).whenComplete((comment, ex) ->
                this.minecraft.execute(() -> {
                    try {
                        if (ex != null) {
                            error = FeedApiClient.unwrap(ex).userMessage();
                            return;
                        }
                        comments.add(comment);
                        post.commentCount = comments.size();
                        input.setValue("");
                        relayout();
                        scroll = Integer.MAX_VALUE;
                        clampScroll();
                    } finally {
                        sending = false;
                        sendButton.active = !post.solved;
                    }
                }));
    }

    private void solve() {
        solveButton.active = false;
        FeedApiClient.INSTANCE.solve(post.id).whenComplete((res, ex) ->
                this.minecraft.execute(() -> {
                    if (ex != null) {
                        solveButton.active = true;
                        error = FeedApiClient.unwrap(ex).userMessage();
                        return;
                    }
                    error = null;
                    post.solved = true;
                    applySolvedState();
                }));
    }

    private void deletePost() {
        if (deleteButton != null) {
            deleteButton.active = false;
        }
        FeedApiClient.INSTANCE.deletePost(post.id).whenComplete((v, ex) ->
                this.minecraft.execute(() -> {
                    if (ex != null) {
                        if (deleteButton != null) {
                            deleteButton.active = true;
                        }
                        error = FeedApiClient.unwrap(ex).userMessage();
                        return;
                    }
                    onClose();
                }));
    }

    private void reportPost() {
        if (reportButton != null) {
            reportButton.active = false;
        }
        FeedApiClient.INSTANCE.report(post.id, null, null).whenComplete((v, ex) ->
                this.minecraft.execute(() -> {
                    if (reportButton != null) {
                        reportButton.active = true;
                    }
                    if (ex != null) {
                        error = FeedApiClient.unwrap(ex).userMessage();
                    } else {
                        error = Component.translatable("feed.screen.post.reported");
                    }
                }));
    }

    private void relayout() {
        int textW = contentWidth() - PAD * 2;
        int headerH = Math.max(AvatarCache.BODY_H, 16);
        postLines = RetroUi.wrap(this.font, post.text != null ? post.text : "", textW);
        int lineStep = this.font.lineHeight + 3;
        postHeight = PAD + headerH + 6 + postLines.size() * lineStep
                + 6 + RetroUi.CHIP_H + PAD + RetroUi.SHADOW_OFFSET;
        if (post.imageUrl != null) {
            postHeight += postImageSize()[1] + 6;
        }

        rows.clear();
        int y = postHeight + CARD_GAP;
        if (nextBefore != null) {
            y += LOAD_OLDER_H;
        }
        for (Comment c : comments) {
            List<String> lines = RetroUi.wrap(this.font, c.text != null ? c.text : "", textW - 8);
            int h = 3 + this.font.lineHeight + 3 + lines.size() * (this.font.lineHeight + 2) + 4;
            if (c.imageUrl != null) {
                h += commentImageSize(c)[1] + 4;
            }
            rows.add(new Row(c, lines, y, h));
            y += h + 6;
        }
        contentHeight = y;
        clampScroll();
    }

    private static final int POST_IMAGE_H = 220;
    private static final int COMMENT_IMAGE_H = 90;
    /** Screen rect of the post picture in the last frame (x, y, w, h), for clicks. */
    private int[] postImageRect;

    private int[] postImageSize() {
        return ImageBox.fit(post.imageW, post.imageH, contentWidth() - PAD * 2, POST_IMAGE_H);
    }

    private int[] commentImageSize(Comment c) {
        return ImageBox.fit(c.imageW, c.imageH, Math.min(200, contentWidth() - PAD * 2 - 8), COMMENT_IMAGE_H);
    }

    private int heightOf(List<Comment> list) {
        int textW = contentWidth() - PAD * 2;
        int h = 0;
        for (Comment c : list) {
            List<String> lines = RetroUi.wrap(this.font, c.text != null ? c.text : "", textW - 8);
            h += 3 + this.font.lineHeight + 3 + lines.size() * (this.font.lineHeight + 2) + 4 + 6;
            if (c.imageUrl != null) {
                h += commentImageSize(c)[1] + 4;
            }
        }
        return h;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, RetroUi.BG);
        if (!post.solved) {
            RetroUi.drawBox(g, contentLeft(), inputTop(), contentWidth(), INPUT_H, false);
        }
        g.fill(0, listBottom() + 2, this.width, listBottom() + 3, RetroUi.SHADOW);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        String t = this.title.getString() + (RetroUi.blink() ? "_" : " ");
        g.drawString(this.font, t, (this.width - this.font.width(t)) / 2, 11, RetroUi.FG, false);

        int bottom = listBottom();
        g.enableScissor(0, TOP, this.width, bottom);
        drawPostCard(g, contentLeft(), TOP - scroll);

        if (nextBefore != null) {
            int rowY = TOP + postHeight + CARD_GAP - scroll;
            String label = loadingOlder ? "..."
                    : Component.translatable("feed.screen.post.load_older").getString();
            int lw = this.font.width(label);
            int lx = this.width / 2 - lw / 2;
            boolean hover = mouseY >= rowY - 2 && mouseY <= rowY + 10
                    && mouseX >= lx - 4 && mouseX <= lx + lw + 4;
            if (hover) {
                g.fill(lx - 4, rowY - 2, lx + lw + 4, rowY + 10, RetroUi.HOVER_BG);
            }
            g.drawString(this.font, label, lx, rowY, hover ? RetroUi.ACCENT : RetroUi.GRAY, false);
            loadOlderY = rowY;
        } else {
            loadOlderY = Integer.MIN_VALUE;
        }

        for (Row row : rows) {
            int cy = TOP + row.y() - scroll;
            if (cy + row.height() >= TOP && cy <= bottom) {
                drawComment(g, row, contentLeft(), cy);
            }
        }
        if (!loading && comments.isEmpty() && error == null) {
            int emptyY = TOP + postHeight + CARD_GAP + (nextBefore != null ? LOAD_OLDER_H : 0) + 6 - scroll;
            g.drawCenteredString(this.font, Component.translatable("feed.screen.post.empty"),
                    this.width / 2, emptyY, RetroUi.GRAY);
        }
        g.disableScissor();

        if (post.solved) {
            g.drawCenteredString(this.font, Component.translatable("feed.screen.post.closed"),
                    this.width / 2, inputTop() + 6, RetroUi.GRAY);
        }
        if (loading) {
            String load = Component.translatable("feed.screen.loading").getString()
                    + (RetroUi.blinkFast() ? "_" : " ");
            g.drawCenteredString(this.font, load, this.width / 2, bottom - 12, RetroUi.GRAY);
        }
        if (error != null) {
            g.drawCenteredString(this.font, error, this.width / 2, bottom - 12, RetroUi.ERROR);
        }
    }

    private void drawPostCard(GuiGraphics g, int x, int y) {
        int w = contentWidth();
        RetroUi.drawBox(g, x, y, w, postHeight, false);

        PostType type = post.postType();
        int ax = x + PAD;
        int ay = y + PAD;
        int skinW = AvatarCache.BODY_W;
        int skinH = AvatarCache.BODY_H;
        ResourceLocation skin = AvatarCache.get(FeedApiClient.avatarKey(post.uuid, post.author), AvatarCache.Kind.BODY);
        if (skin != null) {
            g.blit(skin, ax, ay, 0, 0, skinW, skinH, skinW, skinH);
        } else {
            g.fill(ax, ay, ax + skinW, ay + skinH, RetroUi.GRAY);
            String letter = displayAuthor().substring(0, 1).toUpperCase();
            g.drawString(this.font, letter, ax + (skinW - this.font.width(letter)) / 2,
                    ay + skinH / 2 - 4, RetroUi.BG, false);
        }
        g.renderOutline(ax, ay, skinW, skinH, RetroUi.FG);

        int nx = ax + skinW + PAD;
        RetroUi.drawChip(g, this.font, post.displayPrefix(), nx, ay + 2, post.prefixColor(), false);
        int tx = nx + RetroUi.chipWidth(this.font, post.displayPrefix()) + 5;
        String name = displayAuthor();
        g.drawString(this.font, name, tx, ay + 4, isOwn() ? RetroUi.ACCENT : RetroUi.FG, false);
        String meta = "\u00b7 " + FeedTime.ago(post.createdAt);
        g.drawString(this.font, meta, tx + this.font.width(name) + 4, ay + 4, RetroUi.GRAY, false);

        int headerH = Math.max(AvatarCache.BODY_H, 16);
        int lineY = y + PAD + headerH + 6;
        for (String line : postLines) {
            int lx = x + PAD;
            for (TagText.Seg seg : TagText.segments(line)) {
                g.drawString(this.font, seg.text(), lx, lineY,
                        seg.tag() ? type.accent() : RetroUi.FG, false);
                lx += this.font.width(seg.text());
            }
            lineY += this.font.lineHeight + 3;
        }
        if (post.imageUrl != null) {
            int[] s = postImageSize();
            ImageBox.draw(g, this.font, post.imageUrl, x + PAD, lineY + 3, s[0], s[1]);
            postImageRect = new int[]{x + PAD, lineY + 3, s[0], s[1]};
        } else {
            postImageRect = null;
        }

        int by = y + postHeight - PAD - RetroUi.CHIP_H;
        int rx = x + PAD;
        for (String key : type.reactions()) {
            int count = post.reactionCount(key);
            EmojiIcon icon = EmojiIcon.byKey(key);
            RetroUi.drawReactionChip(g, this.font, icon, count, rx, by,
                    type.accent(), post.mine(key), RetroUi.BG);
            rx += RetroUi.reactionChipWidth(this.font, icon, String.valueOf(count)) + 6;
        }
        String cc = "\u270e " + post.commentCount;
        g.drawString(this.font, cc, x + w - PAD - this.font.width(cc), by + 2, RetroUi.GRAY, false);
    }

    private void drawComment(GuiGraphics g, Row row, int x, int y) {
        Comment c = row.comment();
        g.fill(x, y, x + 2, y + row.height(), RetroUi.SHADOW);

        boolean own = FeedApiClient.isMine(c.authorId);
        int nameColor = own ? RetroUi.ACCENT : (c.fromMod ? RetroUi.FG : RetroUi.GRAY);
        String author = c.author == null || c.author.isEmpty() ? "?" : c.author;
        g.drawString(this.font, author, x + 8, y + 3, nameColor, false);
        String meta = (c.fromMod ? "\u00b7 " : "\u00b7 "
                + Component.translatable("feed.author.discord").getString() + " \u00b7 ")
                + FeedTime.ago(c.createdAt);
        g.drawString(this.font, meta, x + 8 + this.font.width(author) + 4, y + 3,
                RetroUi.GRAY, false);

        int ly = y + 3 + this.font.lineHeight + 3;
        for (String line : row.lines()) {
            int lx = x + 8;
            for (TagText.Seg seg : TagText.segments(line)) {
                g.drawString(this.font, seg.text(), lx, ly,
                        seg.tag() ? RetroUi.ACCENT : RetroUi.FG, false);
                lx += this.font.width(seg.text());
            }
            ly += this.font.lineHeight + 2;
        }
        if (c.imageUrl != null) {
            int[] s = commentImageSize(c);
            ImageBox.draw(g, this.font, c.imageUrl, x + 8, ly + 1, s[0], s[1]);
        }
    }

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

        if (postImageRect != null && mouseX >= postImageRect[0] && mouseX < postImageRect[0] + postImageRect[2]
                && mouseY >= Math.max(postImageRect[1], TOP) && mouseY < Math.min(postImageRect[1] + postImageRect[3], listBottom())) {
            this.minecraft.setScreen(new ImageViewScreen(this, post.imageUrl, post.imageW, post.imageH));
            return true;
        }

        if (nextBefore != null && !loadingOlder && loadOlderY != Integer.MIN_VALUE
                && mouseY >= loadOlderY - 2 && mouseY <= loadOlderY + 10) {
            loadOlder();
            return true;
        }

        int by = TOP - scroll + postHeight - PAD - RetroUi.CHIP_H;
        if (mouseY >= Math.max(by, TOP) && mouseY < Math.min(by + RetroUi.CHIP_H, listBottom())) {
            int rx = contentLeft() + PAD;
            for (String key : post.postType().reactions()) {
                int count = post.reactionCount(key);
                int w = RetroUi.reactionChipWidth(this.font, EmojiIcon.byKey(key), String.valueOf(count));
                if (mouseX >= rx && mouseX < rx + w) {
                    ReactionHelper.toggle(post, key, null);
                    return true;
                }
                rx += w + 6;
            }
        }

        int top = listTop();
        int bottom = listBottom();
        if (mouseY >= top && mouseY < bottom) {
            for (Row row : rows) {
                int y = top + row.y() - scroll;
                if (mouseY < y || mouseY >= y + row.height()) {
                    continue;
                }
                int bodyTop = y + 3 + this.font.lineHeight + 3;
                int lineH = this.font.lineHeight + 2;
                int index = (int) ((mouseY - bodyTop) / lineH);
                if (mouseY >= bodyTop && index >= 0 && index < row.lines().size()) {
                    int lx = contentLeft() + 8;
                    for (TagText.Seg seg : TagText.segments(row.lines().get(index))) {
                        int w = this.font.width(seg.text());
                        if (mouseX >= lx && mouseX < lx + w) {
                            if (seg.tag()) {
                                Minecraft.getInstance().setScreen(
                                        new FeedScreen(seg.text().substring(1).toLowerCase()));
                                return true;
                            }
                            break;
                        }
                        lx += w;
                    }
                }
                break;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && input.visible && input.isFocused()) {
            send();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private String displayAuthor() {
        return post.author == null || post.author.isEmpty() ? "?" : post.author;
    }

    private void clampScroll() {
        int viewport = listBottom() - TOP;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - viewport)));
    }

    private int inputTop() {
        return this.height - 14 - INPUT_H;
    }

    private int listBottom() {
        return inputTop() - 8;
    }

    private int listTop() {
        return TOP;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
