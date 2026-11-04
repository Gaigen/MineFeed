package com.feed.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Centered retro panel with a blinking title, used by the account screens. */
@OnlyIn(Dist.CLIENT)
public abstract class AccountPanelScreen extends RetroScreen {

    protected static final int PAD = 10;
    protected static final int TITLE_H = 16;
    protected static final int BTN_H = 18;
    protected static final int LINE = 10;

    protected int px;
    protected int py;
    protected int pw;
    protected int ph;
    protected Component status = Component.empty();
    protected int statusColor = RetroUi.ERROR;
    protected boolean busy;
    protected final List<EditBox> fields = new ArrayList<>();

    protected AccountPanelScreen(Component title) {
        super(title);
    }

    protected int panelWidth() {
        return Math.min(280, this.width - 24);
    }

    protected int innerX() {
        return px + PAD;
    }

    protected int innerW() {
        return pw - PAD * 2;
    }

    /** Call at the start of init() with the height of everything below the title. */
    protected int layoutPanel(int contentH) {
        fields.clear();
        pw = panelWidth();
        ph = PAD + TITLE_H + contentH + PAD;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);
        return py + PAD + TITLE_H;
    }

    protected EditBox addField(EditBox box) {
        fields.add(box);
        return addRenderableWidget(box);
    }

    protected void setStatus(Component text, int color) {
        status = text == null ? Component.empty() : text;
        statusColor = color;
    }

    protected void drawStatus(GuiGraphics g, int y) {
        if (busy) {
            g.drawString(this.font, Component.translatable("feed.account.working").getString()
                    + (RetroUi.blink() ? "_" : ""), innerX(), y, RetroUi.GRAY, false);
        } else if (!status.getString().isEmpty()) {
            String s = status.getString();
            String clipped = this.font.plainSubstrByWidth(s, innerW());
            g.drawString(this.font, clipped, innerX(), y, statusColor, false);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, RetroUi.BG);
        RetroUi.drawBox(g, px, py, pw, ph, false);
        RetroUi.drawBlinkingTitle(g, this.font, this.title.getString(), innerX(), py + PAD, RetroUi.FG);
        for (EditBox f : fields) {
            if (f.isVisible()) {
                AccountUi.drawField(g, f);
            }
        }
    }

    protected boolean isEnter(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
    }

    protected int buttonsRowWidth(int count, int gap) {
        return (innerW() - gap * (count - 1)) / count;
    }
}
