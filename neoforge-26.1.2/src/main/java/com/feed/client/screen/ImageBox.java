package com.feed.client.screen;

import com.feed.client.PostImages;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Draws a post picture scaled into a box, with a placeholder while it loads. */
public final class ImageBox {

    private ImageBox() {}

    /** {w, h} of a picture iw x ih fitted into maxW x maxH (never upscaled past 2x). */
    public static int[] fit(int iw, int ih, int maxW, int maxH) {
        if (iw <= 0 || ih <= 0) {
            iw = 16;
            ih = 9;
        }
        double k = Math.min(Math.min(maxW / (double) iw, maxH / (double) ih), 2.0);
        return new int[]{Math.max(8, (int) (iw * k)), Math.max(8, (int) (ih * k))};
    }

    public static void draw(GuiGraphicsExtractor g, Font font, PostImages.Tex tex, boolean failed, int x, int y, int w, int h) {
        if (tex != null) {
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, tex.location(), x, y, w, h, 0, 0, tex.w(), tex.h(), tex.w(), tex.h());
            g.outline(x - 1, y - 1, w + 2, h + 2, RetroUi.GRAY);
            return;
        }
        g.fill(x, y, x + w, y + h, RetroUi.HOVER_BG);
        g.outline(x - 1, y - 1, w + 2, h + 2, RetroUi.GRAY);
        String label = failed ? Component.translatable("feed.image.failed").getString()
                : Component.translatable("feed.image.loading").getString() + (RetroUi.blinkFast() ? "_" : "");
        if (font.width(label) < w - 4 && h >= 10) {
            g.text(font, label, x + (w - font.width(label)) / 2, y + (h - 8) / 2, RetroUi.GRAY, false);
        }
    }

    /** Post/comment picture by URL. */
    public static void draw(GuiGraphicsExtractor g, Font font, String url, int x, int y, int w, int h) {
        draw(g, font, PostImages.get(url), PostImages.failed(url), x, y, w, h);
    }
}
