package com.feed.client.screen;

import com.feed.client.FeedApiClient;
import com.feed.data.PostType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

/** Compose panel with type selector. Ctrl+Enter submits. */
@OnlyIn(Dist.CLIENT)
public class ComposeScreen extends Screen {

    private static final int MAX = 500;
    private static final int INPUT_H = 80;
    private static final int PANEL_H = 24 + RetroUi.CHIP_H + 6 + INPUT_H + 12 + 18 + 24;

    private final FeedScreen parent;

    private MultiLineEditBox input;
    private RetroButton sendButton;
    private PostType selected;
    private final String prefill;
    private Component status;
    private boolean sending;

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

        input = new MultiLineEditBox(this.font, inputX, inputY, inputW, INPUT_H,
                Component.translatable("feed.screen.compose.placeholder"), Component.empty());
        input.setCharacterLimit(MAX);
        addRenderableWidget(input);
        if (prefill != null && input.getValue().isEmpty()) {
            input.setValue(prefill.length() > MAX ? prefill.substring(0, MAX) : prefill);
        }

        int buttonY = inputY + INPUT_H + 12;
        int buttonW = (inputW - 8) / 2;
        sendButton = addRenderableWidget(new RetroButton(inputX, buttonY, buttonW, 18,
                Component.translatable("feed.screen.compose.send"), b -> submit()));
        addRenderableWidget(new RetroButton(inputX + buttonW + 8, buttonY, buttonW, 18,
                Component.translatable("feed.screen.compose.cancel"), b -> onClose()));

        setInitialFocus(input);
    }

    private void submit() {
        if (sending) {
            return;
        }
        String text = input.getValue().trim();
        if (text.isEmpty()) {
            status = Component.translatable("feed.screen.compose.empty");
            return;
        }
        sending = true;
        sendButton.active = false;
        status = null;
        FeedApiClient.INSTANCE.createPost(text, selected).whenComplete((post, ex) ->
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
        return t.prefix + " " + t.id.toUpperCase();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, RetroUi.BG);
        // Panel here: Screen.render() redraws background before widgets.
        RetroUi.drawBox(g, panelLeft(), panelTop(), panelWidth(), PANEL_H, false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        int x = panelLeft();
        int y = panelTop();
        int w = panelWidth();

        RetroUi.drawBlinkingTitle(g, this.font,
                Component.translatable(selected.composeTitleKey()).getString().toUpperCase(),
                x + 10, y + 9, selected.accent);
        Component hint = Component.translatable("feed.screen.compose.hint");
        g.drawString(this.font, hint, x + w - 10 - this.font.width(hint), y + 9, RetroUi.GRAY, false);

        int cx = x + 10;
        int cy = y + 24;
        for (PostType t : PostType.values()) {
            String label = chipLabel(t);
            RetroUi.drawChip(g, this.font, label, cx, cy, t.accent, t == selected);
            cx += RetroUi.chipWidth(this.font, label) + 6;
        }

        g.renderOutline(x + 9, y + 24 + RetroUi.CHIP_H + 5, w - 18, INPUT_H + 2, RetroUi.FG);

        int statusY = y + PANEL_H - 16;
        if (sending) {
            String text = Component.translatable("feed.screen.compose.sending").getString()
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