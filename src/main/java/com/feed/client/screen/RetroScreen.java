package com.feed.client.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
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
    public boolean isPauseScreen() {
        return false;
    }
}
