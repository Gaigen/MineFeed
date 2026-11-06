package com.feed.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class RetroButton extends Button {

    private int color = -1;
    private String icon;

    public RetroButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    /** Text/outline color when idle (e.g. accent for the main action); -1 = theme text color. */
    public RetroButton color(int c) {
        this.color = c;
        return this;
    }

    /** Pixel icon drawn before the label (or centered when the label is empty). */
    public RetroButton icon(String name) {
        this.icon = name;
        return this;
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        boolean hover = this.active && this.isHoveredOrFocused();
        int idle = this.color == -1 ? RetroUi.FG : this.color;
        RetroUi.drawBox(g, getX(), getY(), getWidth(), getHeight(), hover);
        if (!hover && this.active && this.color != -1) {
            g.outline(getX(), getY(), getWidth(), getHeight(), idle);
        }
        Font font = Minecraft.getInstance().font;
        int color = !this.active ? RetroUi.GRAY : (hover ? RetroUi.STRONG : idle);
        int iw = icon != null ? PixelIcon.SIZE + (getMessage().getString().isEmpty() ? 0 : 4) : 0;
        int tx = getX() + (getWidth() - font.width(getMessage()) - iw) / 2;
        int ty = getY() + (getHeight() - font.lineHeight) / 2 + 1;
        if (icon != null) {
            PixelIcon.draw(g, icon, tx, getY() + (getHeight() - PixelIcon.SIZE) / 2, color);
        }
        g.text(font, getMessage(), tx + iw, ty, color, false);
    }
}
