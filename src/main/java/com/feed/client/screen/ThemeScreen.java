package com.feed.client.screen;

import com.feed.data.PostType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Theme picker: every theme drawn as a small preview card in its own colors. A click applies and saves. */
@OnlyIn(Dist.CLIENT)
public class ThemeScreen extends ModalScreen {

    private static final int ROW_H = 34;
    private static final int GAP = 5;

    public ThemeScreen(FeedScreen parent) {
        super(Component.translatable("feed.theme.title"), parent);
    }

    @Override
    protected void init() {
        Theme.sync();
        layout(300, 16 + PAD * 2 + 8 + Theme.values().length * (ROW_H + GAP) + 14);
    }

    @Override
    protected void renderContent(GuiGraphics g, int mouseX, int mouseY) {
        int x = innerX();
        int w = innerW();
        int y = bodyTop() + 6;
        for (Theme t : Theme.values()) {
            boolean selected = t == Theme.current();
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H;
            drawPreview(g, t, x, y, w, ROW_H, selected, hover);
            hit(x, y, w, ROW_H, () -> Theme.choose(t));
            y += ROW_H + GAP;
        }
        String hint = tr("feed.theme.hint");
        g.drawString(this.font, hint, x, y + 1, RetroUi.GRAY, false);
    }

    /** A mini card in the theme's own palette, independent of the current one. */
    private void drawPreview(GuiGraphics g, Theme t, int x, int y, int w, int h, boolean selected, boolean hover) {
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, t.shadow);
        g.fill(x, y, x + w, y + h, hover ? t.hover : t.bg);
        g.renderOutline(x, y, w, h, selected ? t.accent : hover ? t.fg : t.gray);
        if (selected) {
            g.fill(x, y, x + 3, y + h, t.accent);
        }
        String name = tr(t.nameKey()) + (selected ? "  \u2713" : "");
        g.drawString(this.font, name, x + 8, y + 6, selected ? t.accent : t.fg, false);
        g.drawString(this.font, tr(t.nameKey() + ".sub"), x + 8, y + 19, t.gray, false);
        int cx = x + w - 6;
        for (int i = PostType.values().length - 1; i >= 0; i--) {
            PostType p = PostType.values()[i];
            String label = p.prefix;
            int cw = RetroUi.chipWidth(this.font, label);
            cx -= cw;
            int color = t.tint(baseOf(p));
            boolean inv = i == 0;
            g.fill(cx, y + 11, cx + cw, y + 11 + RetroUi.CHIP_H, inv ? color : t.bg);
            g.renderOutline(cx, y + 11, cw, RetroUi.CHIP_H, color);
            g.drawString(this.font, label, cx + 5, y + 13, inv ? t.bg : color, false);
            cx -= 4;
        }
    }

    private static int baseOf(PostType p) {
        return switch (p) {
            case RIP -> 0xFFFF5555;
            case HELP -> 0xFFFFFF55;
            case GG -> 0xFF55FF55;
            case TALK -> 0xFF55FFFF;
        };
    }
}
