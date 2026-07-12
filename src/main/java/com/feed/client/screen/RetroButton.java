package com.feed.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class RetroButton extends Button {

    public RetroButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean inverted = this.active && this.isHoveredOrFocused();
        RetroUi.drawBox(g, getX(), getY(), getWidth(), getHeight(), inverted);
        Font font = Minecraft.getInstance().font;
        int color = !this.active ? RetroUi.GRAY : (inverted ? RetroUi.BG : RetroUi.FG);
        int tx = getX() + (getWidth() - font.width(getMessage())) / 2;
        int ty = getY() + (getHeight() - font.lineHeight) / 2 + 1;
        g.drawString(font, getMessage(), tx, ty, color, false);
    }
}
