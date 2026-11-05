package com.feed.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Multi-line text field with the MineFeed frame instead of the vanilla black box. */
@OnlyIn(Dist.CLIENT)
public class RetroTextArea extends MultiLineEditBox {

    public RetroTextArea(Font font, int x, int y, int w, int h, Component placeholder, Component message) {
        super(font, x, y, w, h, placeholder, message);
    }

    @Override
    protected void renderBorder(GuiGraphics g, int x, int y, int w, int h) {
        boolean focused = isFocused();
        g.fill(x, y, x + w, y + h, focused ? RetroUi.HOVER_BG : RetroUi.BG);
        g.renderOutline(x, y, w, h, focused ? RetroUi.ACCENT : RetroUi.GRAY);
    }
}
