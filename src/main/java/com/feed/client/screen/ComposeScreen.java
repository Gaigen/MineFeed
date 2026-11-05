package com.feed.client.screen;

import com.feed.client.FeedApiClient;
import com.feed.client.PostImages;
import com.feed.data.PostType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Compose panel with type selector. Ctrl+Enter submits. */
@OnlyIn(Dist.CLIENT)
public class ComposeScreen extends Screen {

    private static final int MAX = 500;
    private static final int INPUT_H = 80;
    private static final int ATTACH_H = 28;
    private static final int PANEL_H = 24 + RetroUi.CHIP_H + 6 + INPUT_H + 8 + ATTACH_H + 8 + 18 + 24;

    private final FeedScreen parent;

    private MultiLineEditBox input;
    private RetroButton sendButton;
    private PostType selected;
    private final String prefill;
    private Component status;
    private boolean sending;
    private Path attached;
    private String draft;
    private RetroButton attachButton;
    private RetroButton removeButton;

    public ComposeScreen(FeedScreen parent) {
        this(parent, PostType.TALK, null);
    }

    public ComposeScreen(FeedScreen parent, PostType type, String prefill) {
        super(Component.translatable(type.composeTitleKey()));
        this.parent = parent;
        this.selected = type;
        this.prefill = prefill;
    }

    @Override
    protected void init() {
        int x = panelLeft();
        int y = panelTop();
        int w = panelWidth();
        int inputX = x + 10;
        int inputY = y + 24 + RetroUi.CHIP_H + 6;
        int inputW = w - 20;

        input = new RetroTextArea(this.font, inputX, inputY, inputW, INPUT_H,
                Component.translatable("feed.screen.compose.placeholder"), Component.empty());
        input.setCharacterLimit(MAX);
        addRenderableWidget(input);
        if (draft != null) {
            input.setValue(draft);
        } else if (prefill != null && input.getValue().isEmpty()) {
            input.setValue(prefill.length() > MAX ? prefill.substring(0, MAX) : prefill);
        }

        int attachY = inputY + INPUT_H + 8;
        attachButton = addRenderableWidget(new RetroButton(inputX, attachY + (ATTACH_H - 18) / 2, 100, 18,
                Component.translatable("feed.compose.screenshot"), b -> pickScreenshot()));
        removeButton = addRenderableWidget(new RetroButton(inputX + inputW - 70, attachY + (ATTACH_H - 18) / 2, 70, 18,
                Component.translatable("feed.compose.remove_image"), b -> attached = null));
        updateAttachButtons();

        int buttonY = attachY + ATTACH_H + 8;
        Component send = Component.translatable("feed.screen.compose.send");
        Component cancel = Component.translatable("feed.screen.compose.cancel");
        int sw = this.font.width(send) + 16;
        int cw = this.font.width(cancel) + 16;
        sendButton = addRenderableWidget(new RetroButton(inputX + inputW - sw, buttonY, sw, 16, send, b -> submit())
                .color(RetroUi.ACCENT));
        addRenderableWidget(new RetroButton(inputX + inputW - sw - 6 - cw, buttonY, cw, 16, cancel, b -> onClose()));

        setInitialFocus(input);
    }

    private void pickScreenshot() {
        draft = input.getValue();
        this.minecraft.setScreen(new ScreenshotPickerScreen(this, p -> attached = p));
    }

    private void updateAttachButtons() {
        if (attachButton != null) {
            attachButton.visible = attached == null;
            removeButton.visible = attached != null;
            attachButton.active = !sending;
            removeButton.active = !sending;
        }
    }

    @Override
    public void tick() {
        super.tick();
        updateAttachButtons();
    }

    private void submit() {
        if (sending) {
            return;
        }
        String text = input.getValue().trim();
        if (text.isEmpty() && attached == null) {
            status = Component.translatable("feed.screen.compose.empty");
            return;
        }
        sending = true;
        sendButton.active = false;
        status = null;
        Path file = attached;
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
            send(text, jpeg);
            return null;
        });
    }

    private void send(String text, String jpeg) {
        FeedApiClient.INSTANCE.createPost(parent.space(), text, selected, jpeg).whenComplete((post, ex) ->
                this.minecraft.execute(() -> {
                    sending = false;
                    sendButton.active = true;
                    if (ex != null) {
                        status = FeedApiClient.unwrap(ex).userMessage();
                        return;
                    }
                    parent.refresh();
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
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0) {
            int cx = panelLeft() + 10;
            int cy = panelTop() + 24;
            for (PostType t : PostType.values()) {
                int w = RetroUi.chipWidth(this.font, chipLabel(t));
                if (mouseX >= cx && mouseX < cx + w && mouseY >= cy && mouseY < cy + RetroUi.CHIP_H) {
                    selected = t;
                    return true;
                }
                cx += w + 6;
            }
        }
        return false;
    }

    private static String chipLabel(PostType t) {
        return t.prefix + " " + Component.translatable("feed.type." + t.id).getString();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (parent != null && parent.width == this.width && parent.height == this.height) {
            parent.render(g, -1, -1, partialTick);
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,
                    net.minecraft.client.Minecraft.ON_OSX);
            g.fill(0, 0, this.width, this.height, RetroUi.DIM);
        } else {
            g.fill(0, 0, this.width, this.height, RetroUi.BG);
        }
        // Panel here: Screen.render() redraws background before widgets.
        RetroUi.drawBox(g, panelLeft(), panelTop(), panelWidth(), PANEL_H, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int x = panelLeft();
        int y = panelTop();
        int w = panelWidth();

        Component hint = Component.translatable("feed.screen.compose.hint");
        var community = parent.community();
        String title = Component.translatable("feed.compose.title").getString();
        String where = community != null ? community.name() : Component.translatable("feed.space.global").getString();
        g.drawString(this.font, title, x + 10, y + 9, RetroUi.FG, false);
        g.drawString(this.font, com.feed.client.FeedUtil.clip(this.font, " \u00b7 " + where,
                        Math.max(10, w - 30 - this.font.width(title) - this.font.width(hint))),
                x + 10 + this.font.width(title), y + 9, RetroUi.GRAY, false);
        g.drawString(this.font, hint, x + w - 10 - this.font.width(hint), y + 9, RetroUi.GRAY, false);

        int cx = x + 10;
        int cy = y + 24;
        for (PostType t : PostType.values()) {
            String label = chipLabel(t);
            RetroUi.drawChip(g, this.font, label, cx, cy, t.accent(), t == selected);
            cx += RetroUi.chipWidth(this.font, label) + 6;
        }


        int attachY = y + 24 + RetroUi.CHIP_H + 6 + INPUT_H + 8;
        if (attached != null) {
            PostImages.Tex t = PostImages.thumb(attached);
            int th = ATTACH_H - 2;
            int tw = th * 16 / 9;
            int[] s = t != null ? ImageBox.fit(t.w(), t.h(), tw, th) : new int[]{tw, th};
            ImageBox.draw(g, this.font, t, PostImages.thumbFailed(attached), x + 10, attachY + 1 + (th - s[1]) / 2, s[0], s[1]);
            String name = com.feed.client.FeedUtil.clip(this.font, attached.getFileName().toString(), w - 20 - tw - 8 - 76);
            g.drawString(this.font, name, x + 10 + tw + 8, attachY + (ATTACH_H - 8) / 2, RetroUi.FG, false);
        } else {
            String tip = Component.translatable("feed.compose.screenshot_tip").getString();
            g.drawString(this.font, com.feed.client.FeedUtil.clip(this.font, tip, w - 20 - 108), x + 10 + 108,
                    attachY + (ATTACH_H - 8) / 2, RetroUi.GRAY, false);
        }

        int statusY = y + PANEL_H - 18;
        if (sending) {
            String text = Component.translatable(attached != null ? "feed.compose.uploading" : "feed.screen.compose.sending").getString()
                    + (RetroUi.blinkFast() ? "\u25ae" : "");
            g.drawString(this.font, text, x + 10, statusY, RetroUi.GRAY, false);
        } else if (status != null) {
            g.drawString(this.font, status, x + 10, statusY, RetroUi.ERROR, false);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
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
        return Math.max(16, (this.height - PANEL_H) / 2 - 10);
    }
}