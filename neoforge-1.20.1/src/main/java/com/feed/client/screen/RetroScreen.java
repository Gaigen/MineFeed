package com.feed.client.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public abstract class RetroScreen extends Screen {

    protected static final int MAX_CONTENT_W = 360;

    protected RetroScreen(Component title) {
        super(title);
    }

    protected int contentWidth() {
        return Math.min(MAX_CONTENT_W, this.width - 24);
    }

    protected int contentLeft() {
        return (this.width - contentWidth()) / 2;
    }

    @Override
    public void render(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
