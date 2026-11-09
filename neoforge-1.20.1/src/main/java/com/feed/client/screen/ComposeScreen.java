package com.feed.client.screen;

import com.feed.client.FeedApiClient;
import com.feed.client.PostImages;
import com.feed.data.Post;
import com.feed.data.PostType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Compose panel with type selector, coordinates, poll and screenshot; also edits own posts. Ctrl+Enter submits. */
public class ComposeScreen extends Screen {

    private static final int MAX = 500;
    private static final int INPUT_H = 80;
    private static final int INPUT_H_POLL = 50;
    private static final int ATTACH_H = 28;
    private static final int TAGS_H = 16;
    private static final int MAX_TAG_CHIPS = 6;
    private static final int POLL_MAX = 4;
    private static final int POLL_OPTION_MAX = 40;
    private static final int POLL_HEAD = 12;
    private static final int POLL_ROW = AccountUi.FIELD_H + 3;

    private final Screen back;
    private final FeedScreen feed;
    private final String space;
    private final String where;
    /** Post being edited, null when writing a new one. */
    private final Post editing;
    private final Consumer<Post> onSaved;

    private MultiLineEditBox input;
    private Suggest suggest;
    private RetroButton sendButton;
    private PostType selected;
    private final String prefill;
    private Component status;
    private boolean sending;
    private Path attached;
    /** The picture goes out covered (Discord: SPOILER_ file). */
    private boolean imageSpoiler;
    /** 18+ post of the global feed (only offered to players who turned on "Show 18+"). */
    private boolean nsfw;
    /** Posting into an 18+ community: every post there is 18+, the chip is just shown. */
    private final boolean nsfwSpace;
    private String draft;
    private RetroButton attachButton;
    private RetroButton removeButton;
    private RetroButton coordsButton;
    private RetroButton pollButton;
    /** Poll options being typed; null = no poll. Kept across re-init. */
    private List<String> poll;
    private final List<EditBox> pollFields = new ArrayList<>();
    /** Focus to move to after the click that rebuilt the widgets (the click itself focuses the old button). */
    private net.minecraft.client.gui.components.events.GuiEventListener pendingFocus;
    /** Popular #tags of the space for one-click insert; null while loading. */
    private List<FeedApiClient.HashtagCount> popularTags;
    private boolean tagsRequested;

    public ComposeScreen(FeedScreen parent) {
        this(parent, PostType.TALK, null);
    }

    public ComposeScreen(FeedScreen parent, PostType type, String prefill) {
        super(Component.translatable(type.composeTitleKey()));
        this.back = parent;
        this.feed = parent;
        this.space = parent.space();
        var c = parent.community();
        this.where = c != null ? c.name() : Component.translatable("feed.space.global").getString();
        this.nsfwSpace = c != null && c.nsfw();
        this.selected = type;
        this.prefill = prefill;
        this.editing = null;
        this.onSaved = null;
    }

    /** Edit mode: changes only the text of my post, then returns to back. */
    public ComposeScreen(Screen back, Post post, String where, Consumer<Post> onSaved) {
        super(Component.translatable("feed.compose.edit_title"));
        this.back = back;
        this.feed = back instanceof FeedScreen f ? f : null;
        this.space = post.space;
        this.where = where;
        this.nsfwSpace = false;
        this.selected = post.postType();
        this.prefill = post.text;
        this.editing = post;
        this.onSaved = onSaved;
    }

    private boolean editMode() {
        return editing != null;
    }

    private int inputH() {
        return poll != null ? INPUT_H_POLL : INPUT_H;
    }

    private int pollH() {
        return poll == null ? 0 : POLL_HEAD + ((poll.size() + 1) / 2) * POLL_ROW + 2;
    }

    private int attachH() {
        return editMode() ? 0 : ATTACH_H;
    }

    private int panelH() {
        return 24 + RetroUi.CHIP_H + 6 + inputH() + 4 + TAGS_H + pollH() + attachH() + 8 + 18 + 24;
    }

    private int inputY() {
        return panelTop() + 24 + RetroUi.CHIP_H + 6;
    }

    private int tagRowY() {
        return inputY() + inputH() + 4;
    }

    private int pollY() {
        return tagRowY() + TAGS_H;
    }

    private int attachY() {
        return pollY() + pollH();
    }

    @Override
    protected void init() {
        int x = panelLeft();
        int w = panelWidth();
        int inputX = x + 10;
        int inputY = inputY();
        int inputW = w - 20;

        input = new RetroTextArea(this.font, inputX, inputY, inputW, inputH(),
                Component.translatable("feed.screen.compose.placeholder"), Component.empty());
        input.setCharacterLimit(MAX);
        addRenderableWidget(input);
        if (draft != null) {
            input.setValue(draft);
        } else if (prefill != null && input.getValue().isEmpty()) {
            input.setValue(prefill.length() > MAX ? prefill.substring(0, MAX) : prefill);
        }
        suggest = new Suggest(Suggest.of(input), () -> space);

        if (!tagsRequested) {
            tagsRequested = true;
            FeedApiClient.INSTANCE.hashtags(space, null).whenComplete((list, ex) -> this.minecraft.execute(() ->
                    popularTags = ex != null || list == null ? List.of() : list));
        }

        pollFields.clear();
        if (poll != null) {
            int colW = (inputW - 6) / 2;
            for (int i = 0; i < poll.size(); i++) {
                int fx = inputX + (i % 2) * (colW + 6);
                int fy = pollY() + POLL_HEAD + (i / 2) * POLL_ROW;
                final int idx = i;
                int fieldW = poll.size() > 2 ? colW - 14 : colW;
                EditBox f = AccountUi.field(this.font, fx, fy, fieldW, POLL_OPTION_MAX,
                        Component.translatable("feed.compose.poll.option", i + 1));
                f.setValue(poll.get(i));
                f.setResponder(s -> poll.set(idx, s));
                pollFields.add(addRenderableWidget(f));
                if (poll.size() > 2) {
                    addRenderableWidget(new RetroButton(fx + colW - 12, fy + 2, 12, 12, Component.literal("\u00d7"),
                            b -> removeOption(idx)));
                }
            }
            if (poll.size() < POLL_MAX) {
                Component add = Component.translatable("feed.compose.poll.add");
                int aw = this.font.width(add) + 10;
                addRenderableWidget(new RetroButton(inputX + inputW - aw, pollY() - 1, aw, 11, add, b -> addOption()));
            }
        }

        int attachY = attachY();
        if (!editMode()) {
            attachButton = addRenderableWidget(new RetroButton(inputX, attachY + (ATTACH_H - 18) / 2, 100, 18,
                    Component.translatable("feed.compose.screenshot"), b -> pickScreenshot()));
            removeButton = addRenderableWidget(new RetroButton(inputX + inputW - 70, attachY + (ATTACH_H - 18) / 2, 70, 18,
                    Component.translatable("feed.compose.remove_image"), b -> {
                        attached = null;
                        imageSpoiler = false;
                    }));
        } else {
            attachButton = null;
            removeButton = null;
        }

        int buttonY = attachY + attachH() + 8;
        Component send = Component.translatable(editMode() ? "feed.compose.save" : "feed.screen.compose.send");
        Component cancel = Component.translatable("feed.screen.compose.cancel");
        int sw = this.font.width(send) + 16;
        int cw = this.font.width(cancel) + 16;
        sendButton = addRenderableWidget(new RetroButton(inputX + inputW - sw, buttonY, sw, 16, send, b -> submit())
                .color(RetroUi.ACCENT));
        addRenderableWidget(new RetroButton(inputX + inputW - sw - 6 - cw, buttonY, cw, 16, cancel, b -> onClose()));

        Component coords = Component.translatable("feed.compose.coords");
        int bw = this.font.width(coords) + 16 + PixelIcon.SIZE;
        coordsButton = addRenderableWidget(new RetroButton(inputX, buttonY, bw, 16, coords, b -> insertCoords()).icon("pos"));
        coordsButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("feed.compose.coords_tip")));
        if (!editMode()) {
            Component pl = Component.translatable(poll == null ? "feed.compose.poll" : "feed.compose.poll.remove");
            int pw = this.font.width(pl) + 16 + PixelIcon.SIZE;
            pollButton = addRenderableWidget(new RetroButton(inputX + bw + 4, buttonY, pw, 16, pl, b -> togglePoll()).icon("poll"));
        } else {
            pollButton = null;
        }
        updateButtons();

        setInitialFocus(input);
    }

    private void rebuild() {
        draft = input.getValue();
        rebuildWidgets();
    }

    private void togglePoll() {
        poll = poll == null ? new ArrayList<>(List.of("", "")) : null;
        rebuild();
        pendingFocus = pollFields.isEmpty() ? input : pollFields.get(0);
    }

    private void addOption() {
        if (poll != null && poll.size() < POLL_MAX) {
            poll.add("");
            rebuild();
            pendingFocus = pollFields.get(pollFields.size() - 1);
        }
    }

    private void removeOption(int i) {
        if (poll != null && poll.size() > 2) {
            poll.remove(i);
            rebuild();
            pendingFocus = pollFields.get(Math.min(i, pollFields.size() - 1));
        }
    }

    /** Puts "[x y z]" of where I stand at the cursor. */
    private void insertCoords() {
        var mc = this.minecraft;
        if (mc.player == null || mc.level == null) {
            return;
        }
        var pos = mc.player.blockPosition();
        String c = TagText.coords(pos.getX(), pos.getY(), pos.getZ(), mc.level.dimension().location().toString());
        var tf = ((com.feed.mixin.MultiLineEditBoxAccessor) input).feed$textField();
        String v = tf.value();
        int cur = Math.min(tf.cursor(), v.length());
        String before = cur > 0 && !Character.isWhitespace(v.charAt(cur - 1)) ? " " : "";
        String after = cur < v.length() && !Character.isWhitespace(v.charAt(cur)) ? " " : "";
        if (v.length() + before.length() + c.length() + after.length() > MAX) {
            return;
        }
        tf.insertText(before + c + after);
        pendingFocus = input;
    }

    private void pickScreenshot() {
        draft = input.getValue();
        this.minecraft.setScreen(new ScreenshotPickerScreen(this, p -> attached = p));
    }

    private void updateButtons() {
        if (attachButton != null) {
            attachButton.visible = attached == null;
            removeButton.visible = attached != null;
            attachButton.active = !sending;
            removeButton.active = !sending;
        }
        if (coordsButton != null) {
            coordsButton.active = !sending && this.minecraft.player != null && this.minecraft.level != null;
        }
        if (pollButton != null) {
            pollButton.active = !sending;
        }
    }

    @Override
    public void tick() {
        super.tick();
        updateButtons();
        if (suggest != null) {
            suggest.tick();
        }
    }

    private void submit() {
        if (sending) {
            return;
        }
        String text = input.getValue().trim();
        if (editMode()) {
            if (text.isEmpty() && editing.imageUrl == null) {
                status = Component.translatable("feed.screen.compose.empty");
                return;
            }
            if (text.equals(editing.text == null ? "" : editing.text.trim())) {
                onClose();
                return;
            }
            sending = true;
            sendButton.active = false;
            status = null;
            FeedApiClient.INSTANCE.editPost(editing.id, text).whenComplete((post, ex) -> this.minecraft.execute(() -> {
                sending = false;
                sendButton.active = true;
                if (ex != null) {
                    status = FeedApiClient.unwrap(ex).userMessage();
                    return;
                }
                editing.text = post.text;
                editing.edited = true;
                editing.tags = post.tags;
                if (onSaved != null) {
                    onSaved.accept(editing);
                }
                onClose();
            }));
            return;
        }
        if (text.isEmpty() && attached == null) {
            status = Component.translatable("feed.screen.compose.empty");
            return;
        }
        List<String> options = null;
        if (poll != null) {
            options = new ArrayList<>();
            for (String o : poll) {
                String t = o.trim();
                if (t.isEmpty()) {
                    continue;
                }
                for (String prev : options) {
                    if (prev.equalsIgnoreCase(t)) {
                        status = Component.translatable("feed.compose.poll.duplicate");
                        return;
                    }
                }
                options.add(t);
            }
            if (options.size() < 2) {
                status = Component.translatable("feed.compose.poll.need_two");
                return;
            }
        }
        sending = true;
        sendButton.active = false;
        status = null;
        Path file = attached;
        List<String> pollOptions = options;
        CompletableFuture<String> image = file == null ? CompletableFuture.completedFuture(null)
                : CompletableFuture.supplyAsync(() -> {
                    try {
                        return PostImages.encodeForUpload(file);
                    } catch (Exception e) {
                        throw new IllegalStateException("image", e);
                    }
                });
        image.handle((jpeg, ex) -> {
            if (ex != null) {
                this.minecraft.execute(() -> {
                    sending = false;
                    sendButton.active = true;
                    status = Component.translatable("feed.compose.image_failed");
                });
                return null;
            }
            send(text, jpeg, pollOptions, nsfw && !nsfwSpace, file != null && imageSpoiler);
            return null;
        });
    }

    private void send(String text, String jpeg, List<String> pollOptions, boolean asNsfw, boolean spoiler) {
        FeedApiClient.INSTANCE.createPost(space, text, selected, jpeg, pollOptions, asNsfw, spoiler).whenComplete((post, ex) ->
                this.minecraft.execute(() -> {
                    sending = false;
                    sendButton.active = true;
                    if (ex != null) {
                        status = FeedApiClient.unwrap(ex).userMessage();
                        return;
                    }
                    if (feed != null) {
                        feed.refresh();
                    }
                    onClose();
                }));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)
                && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            submit();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB && getFocused() == input && suggest != null && suggest.tab()) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && suggest != null && suggest.click(this.font, panelLeft() + 10, tagRowY(), tagRowWidth(), mouseX, mouseY)) {
            setFocused(input);
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            if (pendingFocus != null) {
                if (getFocused() != null) {
                    getFocused().setFocused(false);
                }
                setFocused(pendingFocus);
                pendingFocus = null;
            }
            return true;
        }
        if (button == 0 && !editMode()) {
            int cx = panelLeft() + 10;
            int cy = panelTop() + 24;
            if (nsfwChipShown() && !nsfwSpace
                    && inside(mouseX, mouseY, nsfwChipX(), cy, RetroUi.chipWidth(this.font, PostCard.NSFW), RetroUi.CHIP_H)) {
                nsfw = !nsfw;
                return true;
            }
            if (attached != null && removeButton != null
                    && inside(mouseX, mouseY, spoilerChipX(), spoilerChipY(), RetroUi.chipWidth(this.font, spoilerLabel()), RetroUi.CHIP_H)) {
                imageSpoiler = !imageSpoiler;
                return true;
            }
            for (PostType t : PostType.values()) {
                int w = RetroUi.chipWidth(this.font, chipLabel(t));
                if (mouseX >= cx && mouseX < cx + w && mouseY >= cy && mouseY < cy + RetroUi.CHIP_H) {
                    selected = t;
                    return true;
                }
                cx += w + 6;
            }
            return clickTagRow(mouseX, mouseY);
        }
        if (button == 0) {
            return clickTagRow(mouseX, mouseY);
        }
        return false;
    }

    /** "18+" chip at the right of the type row: clickable in the global feed, a plain mark in an 18+ community. */
    private boolean nsfwChipShown() {
        if (editMode()) {
            return false;
        }
        if (nsfwSpace) {
            return true;
        }
        var a = com.feed.client.account.AccountManager.INSTANCE.account();
        return (space == null || "global".equals(space)) && a != null && a.showNsfw();
    }

    private int nsfwChipX() {
        return panelLeft() + panelWidth() - 10 - RetroUi.chipWidth(this.font, PostCard.NSFW);
    }

    private String spoilerLabel() {
        return (imageSpoiler ? "\u2713 " : "") + Component.translatable("feed.spoiler").getString();
    }

    private int spoilerChipX() {
        return removeButton.getX() - 6 - RetroUi.chipWidth(this.font, spoilerLabel());
    }

    private int spoilerChipY() {
        return attachY() + (ATTACH_H - RetroUi.CHIP_H) / 2;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private static String chipLabel(PostType t) {
        return t.prefix + " " + Component.translatable("feed.type." + t.id).getString();
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        if (back != null && back.width == this.width && back.height == this.height) {
            back.render(g, -1, -1, net.minecraft.client.Minecraft.getInstance().getFrameTime());
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,
                    net.minecraft.client.Minecraft.ON_OSX);
            g.fill(0, 0, this.width, this.height, RetroUi.DIM);
        } else {
            g.fill(0, 0, this.width, this.height, RetroUi.BG);
        }
        // Panel and field frames here: Screen.render() redraws background before widgets.
        RetroUi.drawBox(g, panelLeft(), panelTop(), panelWidth(), panelH(), false);
        for (EditBox f : pollFields) {
            AccountUi.drawField(g, f);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);

        int x = panelLeft();
        int y = panelTop();
        int w = panelWidth();

        Component hint = Component.translatable(editMode() ? "feed.compose.save_hint" : "feed.screen.compose.hint");
        String title = Component.translatable(editMode() ? "feed.compose.edit_title" : "feed.compose.title").getString();
        g.drawString(this.font, title, x + 10, y + 9, RetroUi.FG, false);
        g.drawString(this.font, com.feed.client.FeedUtil.clip(this.font, " \u00b7 " + where,
                        Math.max(10, w - 30 - this.font.width(title) - this.font.width(hint))),
                x + 10 + this.font.width(title), y + 9, RetroUi.GRAY, false);
        g.drawString(this.font, hint, x + w - 10 - this.font.width(hint), y + 9, RetroUi.GRAY, false);

        int cx = x + 10;
        int cy = y + 24;
        for (PostType t : PostType.values()) {
            if (editMode() && t != selected) {
                continue;
            }
            String label = chipLabel(t);
            RetroUi.drawChip(g, this.font, label, cx, cy, t.accent(), t == selected);
            cx += RetroUi.chipWidth(this.font, label) + 6;
        }
        if (nsfwChipShown()) {
            int nx = nsfwChipX();
            boolean hv = !nsfwSpace && inside(mouseX, mouseY, nx, cy, RetroUi.chipWidth(this.font, PostCard.NSFW), RetroUi.CHIP_H);
            RetroUi.drawChip(g, this.font, PostCard.NSFW, nx, cy, hv && !nsfw ? RetroUi.FG : RetroUi.ERROR, nsfw || nsfwSpace);
            if (hv) {
                setTooltipForNextRenderPass(Component.translatable(nsfw ? "feed.compose.nsfw_on" : "feed.compose.nsfw_tip"));
            }
        }
        if (editMode()) {
            g.drawString(this.font, com.feed.client.FeedUtil.clip(this.font,
                            Component.translatable("feed.compose.edit_tip").getString(), x + w - 10 - cx),
                    cx, cy + 2, RetroUi.GRAY, false);
        }

        if (!suggest.render(g, this.font, x + 10, tagRowY(), tagRowWidth(), mouseX, mouseY, 0)) {
            renderTagRow(g, x + 10, tagRowY(), w - 20, mouseX, mouseY);
        }

        if (poll != null) {
            g.drawString(this.font, Component.translatable("feed.compose.poll.label"), x + 10, pollY() + 1, RetroUi.GRAY, false);
        }

        int attachY = attachY();
        if (editMode()) {
            // no attachments when editing
        } else if (attached != null) {
            PostImages.Tex t = PostImages.thumb(attached);
            int th = ATTACH_H - 2;
            int tw = th * 16 / 9;
            int[] s = t != null ? ImageBox.fit(t.w(), t.h(), tw, th) : new int[]{tw, th};
            ImageBox.draw(g, this.font, t, PostImages.thumbFailed(attached), x + 10, attachY + 1 + (th - s[1]) / 2, s[0], s[1]);
            int sx = spoilerChipX();
            String name = com.feed.client.FeedUtil.clip(this.font, attached.getFileName().toString(), Math.max(10, sx - 6 - (x + 10 + tw + 8)));
            g.drawString(this.font, name, x + 10 + tw + 8, attachY + (ATTACH_H - 8) / 2, RetroUi.FG, false);
            String sl = spoilerLabel();
            boolean hv = inside(mouseX, mouseY, sx, spoilerChipY(), RetroUi.chipWidth(this.font, sl), RetroUi.CHIP_H);
            RetroUi.drawChip(g, this.font, sl, sx, spoilerChipY(), hv && !imageSpoiler ? RetroUi.FG : RetroUi.ACCENT, imageSpoiler);
            if (hv) {
                setTooltipForNextRenderPass(Component.translatable("feed.compose.spoiler_tip"));
            }
        } else {
            String tip = Component.translatable("feed.compose.screenshot_tip").getString();
            g.drawString(this.font, com.feed.client.FeedUtil.clip(this.font, tip, w - 20 - 108), x + 10 + 108,
                    attachY + (ATTACH_H - 8) / 2, RetroUi.GRAY, false);
        }

        int statusY = y + panelH() - 18;
        if (sending) {
            String key = editMode() ? "feed.compose.saving" : attached != null ? "feed.compose.uploading" : "feed.screen.compose.sending";
            String text = Component.translatable(key).getString() + (RetroUi.blinkFast() ? "\u25ae" : "");
            g.drawString(this.font, text, x + 10, statusY, RetroUi.GRAY, false);
        } else if (status != null) {
            g.drawString(this.font, status, x + 10, statusY, RetroUi.ERROR, false);
        }
    }

    private int tagRowWidth() {
        return panelWidth() - 20 - this.font.width("500/500") - 10;
    }

    /** Chips of popular tags under the text (left of the character counter); a click adds the tag. */
    private void renderTagRow(GuiGraphics g, int x, int y, int w, int mouseX, int mouseY) {
        int limit = x + w - this.font.width("500/500") - 10;
        List<FeedApiClient.HashtagCount> tags = visibleTags();
        if (tags.isEmpty()) {
            if (popularTags != null) {
                String tip = Component.translatable("feed.compose.tag_tip").getString();
                g.drawString(this.font, com.feed.client.FeedUtil.clip(this.font, tip, limit - x), x, y + 2, RetroUi.GRAY, false);
            }
            return;
        }
        int cx = x;
        for (FeedApiClient.HashtagCount t : tags) {
            String label = "#" + t.tag();
            int cw = RetroUi.chipWidth(this.font, label);
            if (cx + cw > limit) {
                break;
            }
            boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= y && mouseY < y + RetroUi.CHIP_H;
            RetroUi.drawChip(g, this.font, label, cx, y, hover ? RetroUi.FG : RetroUi.CYAN, false);
            cx += cw + 4;
        }
    }

    /** Popular tags not yet in the text. */
    private List<FeedApiClient.HashtagCount> visibleTags() {
        if (popularTags == null || input == null) {
            return List.of();
        }
        java.util.Set<String> used = new java.util.HashSet<>();
        for (TagText.Seg seg : TagText.segments(input.getValue())) {
            if (seg.kind() == TagText.Kind.HASHTAG) {
                used.add(TagText.normalizeHashtag(seg.text()));
            }
        }
        List<FeedApiClient.HashtagCount> out = new ArrayList<>();
        for (FeedApiClient.HashtagCount t : popularTags) {
            if (!used.contains(t.tag()) && out.size() < MAX_TAG_CHIPS) {
                out.add(t);
            }
        }
        return out;
    }

    private boolean clickTagRow(double mouseX, double mouseY) {
        if (suggest != null && suggest.active()) {
            return false;
        }
        int x = panelLeft() + 10;
        int y = tagRowY();
        int limit = x + tagRowWidth();
        if (mouseY >= y && mouseY < y + RetroUi.CHIP_H) {
            int cx = x;
            for (FeedApiClient.HashtagCount t : visibleTags()) {
                int cw = RetroUi.chipWidth(this.font, "#" + t.tag());
                if (cx + cw > limit) {
                    break;
                }
                if (mouseX >= cx && mouseX < cx + cw && !sending) {
                    addTag(t.tag());
                    return true;
                }
                cx += cw + 4;
            }
        }
        return false;
    }

    private void addTag(String tag) {
        String v = input.getValue();
        String add = (v.isEmpty() || Character.isWhitespace(v.charAt(v.length() - 1)) ? "" : " ") + "#" + tag + " ";
        if (v.length() + add.length() > MAX) {
            return;
        }
        input.setValue(v + add);
        setFocused(input);
        input.setFocused(true);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(back);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int panelWidth() {
        return Math.min(360, this.width - 32);
    }

    private int panelLeft() {
        return (this.width - panelWidth()) / 2;
    }

    private int panelTop() {
        int free = (this.height - panelH()) / 2;
        return Math.max(Math.min(16, Math.max(2, free)), free - 10);
    }
}
