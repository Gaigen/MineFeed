package com.feed.client.screen;

import com.feed.data.PostType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Themes page: every theme as a mini preview in its own colors, plus the GUI scale. A click applies and saves. */
@OnlyIn(Dist.CLIENT)
public class ThemeScreen extends ShellScreen {

    private static final int TOP = 24;
    private static final int CARD_H = 62;
    private static final int GAP = 8;
    private static final int[] SCALES = {0, 2, 3, 4};

    private int scroll;
    private int contentHeight;

    public ThemeScreen(FeedScreen home) {
        super(Component.translatable("feed.theme.title"), home);
    }

    @Override
    protected String navId() {
        return "themes";
    }

    @Override
    protected void init() {
        Theme.sync();
    }

    private int cols() {
        return contentWidth() >= 300 ? 3 : 2;
    }

    private int cardW() {
        return (contentWidth() - GAP * (cols() - 1)) / cols();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        int x = contentLeft();
        drawTitle(g, this.title.getString(), tr("feed.theme.sub"), x, TITLE_Y, x + contentWidth());
        int bottom = this.height - 4;
        g.enableScissor(sidebarW(), TOP - 2, this.width, bottom);
        int y0 = TOP - scroll;
        Theme[] all = Theme.values();
        int cw = cardW();
        for (int i = 0; i < all.length; i++) {
            int cx = x + (i % cols()) * (cw + GAP);
            int cy = y0 + (i / cols()) * (CARD_H + 10);
            boolean hover = mouseY >= TOP && mouseY < bottom && mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + CARD_H;
            drawPreview(g, all[i], cx, cy, cw, all[i] == Theme.current(), hover);
        }
        int y = y0 + (all.length + cols() - 1) / cols() * (CARD_H + 10) + 2;
        g.drawString(this.font, tr("feed.theme.hint"), x, y, RetroUi.GRAY, false);
        y += 16;
        String label = tr("feed.theme.scale");
        g.drawString(this.font, label, x, y + 2, RetroUi.GRAY, false);
        int bx = x + this.font.width(label) + 6;
        int current = this.minecraft.options.guiScale().get();
        for (int v : SCALES) {
            String l = v == 0 ? tr("feed.theme.scale.auto") : String.valueOf(v);
            int w = RetroUi.chipWidth(this.font, l);
            boolean on = current == v;
            boolean hover = mouseX >= bx && mouseX < bx + w && mouseY >= y && mouseY < y + RetroUi.CHIP_H;
            RetroUi.drawChip(g, this.font, l, bx, y, on ? RetroUi.ACCENT : hover ? RetroUi.FG : RetroUi.GRAY, on);
            bx += w + 3;
        }
        contentHeight = y + RetroUi.CHIP_H + 6 - y0;
        g.disableScissor();
        drawScrollbar(g, TOP, bottom, scroll, contentHeight);
    }

    /** A mini feed in the theme's own palette, independent of the current one. */
    private void drawPreview(GuiGraphics g, Theme t, int x, int y, int w, boolean selected, boolean hover) {
        g.fill(x + 3, y + 3, x + w + 3, y + CARD_H + 3, t.shadow);
        g.fill(x, y, x + w, y + CARD_H, t.page);
        int pw = w - 14;
        g.fill(x + 8, y + 8, x + 8 + pw, y + 34, t.shadow);
        g.fill(x + 6, y + 6, x + 6 + pw, y + 32, t.bg);
        g.renderOutline(x + 6, y + 6, pw, 26, t.gray);
        g.fill(x + 10, y + 10, x + 18, y + 18, t.accent);
        g.fill(x + 21, y + 11, x + 21 + Math.min(34, pw - 40), y + 13, t.fg);
        g.fill(x + 21, y + 15, x + 21 + Math.min(22, pw - 40), y + 17, t.gray);
        g.fill(x + 10, y + 22, x + 10 + Math.min(30, pw - 10), y + 24, t.fg);
        g.fill(x + 10, y + 26, x + 10 + Math.min(44, pw - 10), y + 28, t.fg);
        PostType[] types = PostType.values();
        for (int j = 0; j < types.length; j++) {
            int c = t.tint(baseOf(types[j]));
            int sx = x + w - 16 - j * 7;
            g.fill(sx, y + 10, sx + 5, y + 15, c);
        }
        g.renderOutline(x, y, w, CARD_H, selected ? t.accent : hover ? t.fg : t.gray);
        g.drawString(this.font, com.feed.client.FeedUtil.clip(this.font, tr(t.nameKey()), w - 10), x + 6, y + 40, t.fg, false);
        if (selected) {
            g.drawString(this.font, tr("feed.theme.selected"), x + 6, y + 50, t.accent, false);
        } else {
            g.drawString(this.font, com.feed.client.FeedUtil.clip(this.font, hover ? tr("feed.theme.apply") : tr(t.nameKey() + ".sub"), w - 10),
                    x + 6, y + 50, t.gray, false);
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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0 || mouseY < TOP || mouseY >= this.height - 4) {
            return false;
        }
        int x = contentLeft();
        int y0 = TOP - scroll;
        Theme[] all = Theme.values();
        int cw = cardW();
        for (int i = 0; i < all.length; i++) {
            int cx = x + (i % cols()) * (cw + GAP);
            int cy = y0 + (i / cols()) * (CARD_H + 10);
            if (mouseX >= cx && mouseX < cx + cw && mouseY >= cy && mouseY < cy + CARD_H) {
                Theme.choose(all[i]);
                return true;
            }
        }
        int y = y0 + (all.length + cols() - 1) / cols() * (CARD_H + 10) + 18;
        if (mouseY >= y && mouseY < y + RetroUi.CHIP_H) {
            int bx = x + this.font.width(tr("feed.theme.scale")) + 6;
            for (int v : SCALES) {
                String l = v == 0 ? tr("feed.theme.scale.auto") : String.valueOf(v);
                int w = RetroUi.chipWidth(this.font, l);
                if (mouseX >= bx && mouseX < bx + w) {
                    if (this.minecraft.options.guiScale().get() != v) {
                        this.minecraft.options.guiScale().set(v);
                        this.minecraft.options.save();
                        this.minecraft.resizeDisplay();
                    }
                    return true;
                }
                bx += w + 3;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (super.mouseScrolled(mouseX, mouseY, dx, dy)) {
            return true;
        }
        int viewport = this.height - 4 - TOP;
        scroll = Math.max(0, Math.min(Math.max(0, contentHeight - viewport), scroll - (int) (dy * SCROLL_STEP)));
        return true;
    }
}
