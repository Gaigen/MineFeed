package com.feed.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** A post picture over the whole screen. Click or Esc closes. */
@OnlyIn(Dist.CLIENT)
public class ImageViewScreen extends Screen {

    private final Screen parent;
    private final String url;
    private final int iw;
    private final int ih;

    public ImageViewScreen(Screen parent, String url, int iw, int ih) {
        super(Component.empty());
        this.parent = parent;
        this.url = url;
        this.iw = iw;
        this.ih = ih;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, 0xF0000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int[] s = ImageBox.fit(iw, ih, this.width - 24, this.height - 34);
        ImageBox.draw(g, this.font, url, (this.width - s[0]) / 2, (this.height - 10 - s[1]) / 2, s[0], s[1]);
        String hint = Component.translatable("feed.image.close").getString();
        g.drawString(this.font, hint, (this.width - this.font.width(hint)) / 2, this.height - 14, RetroUi.GRAY, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        onClose();
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
