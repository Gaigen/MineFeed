package com.feed.client.screen;

import com.feed.client.PostImages;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Grid of the latest screenshots from the game's screenshots folder; a click picks one. */
public class ScreenshotPickerScreen extends Screen {

    private static final int MAX_FILES = 48;
    private static final int COLS = 4;
    private static final int GAP = 6;
    private static final int PAD = 10;

    private final Screen parent;
    private final Consumer<Path> onPick;
    private final List<Path> files = new ArrayList<>();
    private int scroll;
    private boolean openHover;

    public ScreenshotPickerScreen(Screen parent, Consumer<Path> onPick) {
        super(Component.translatable("feed.shots.title"));
        this.parent = parent;
        this.onPick = onPick;
    }

    private Path folder() {
        return this.minecraft.gameDirectory.toPath().resolve("screenshots");
    }

    @Override
    protected void init() {
        files.clear();
        Path dir = folder();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return Files.isRegularFile(p) && (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg"));
                }).sorted(Comparator.comparingLong(ScreenshotPickerScreen::modified).reversed())
                        .limit(MAX_FILES).forEach(files::add);
            } catch (IOException ignored) {
                // empty list
            }
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private int panelW() {
        return Math.min(420, this.width - 24);
    }

    private int panelX() {
        return (this.width - panelW()) / 2;
    }

    private int panelY() {
        return 12;
    }

    private int panelH() {
        return this.height - 24;
    }

    private int gridTop() {
        return panelY() + 28;
    }

    private int gridBottom() {
        return panelY() + panelH() - 22;
    }

    private int cellW() {
        return (panelW() - PAD * 2 - GAP * (COLS - 1)) / COLS;
    }

    private int cellH() {
        return cellW() * 9 / 16;
    }

    private int contentH() {
        int rows = (files.size() + COLS - 1) / COLS;
        return rows * (cellH() + GAP);
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        g.fill(0, 0, this.width, this.height, RetroUi.BG);
        RetroUi.drawBox(g, panelX(), panelY(), panelW(), panelH(), false);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        super.render(g, mouseX, mouseY, partialTick);
        int x = panelX() + PAD;
        RetroUi.drawBlinkingTitle(g, this.font, this.title.getString().toUpperCase(), x, panelY() + 9, RetroUi.ACCENT);
        String esc = Component.translatable("feed.image.close").getString();
        g.drawString(this.font, esc, panelX() + panelW() - PAD - this.font.width(esc), panelY() + 9, RetroUi.GRAY, false);

        int top = gridTop();
        int bottom = gridBottom();
        if (files.isEmpty()) {
            AccountUi.drawWrapped(g, this.font, Component.translatable("feed.shots.empty").getString(), x, top + 6, panelW() - PAD * 2, RetroUi.GRAY);
        }
        g.enableScissor(panelX() + 1, top, panelX() + panelW() - 1, bottom);
        int cw = cellW();
        int ch = cellH();
        for (int i = 0; i < files.size(); i++) {
            int cx = x + (i % COLS) * (cw + GAP);
            int cy = top + (i / COLS) * (ch + GAP) - scroll;
            if (cy + ch < top || cy > bottom) {
                continue;
            }
            Path f = files.get(i);
            PostImages.Tex t = PostImages.thumb(f);
            boolean hover = mouseX >= cx && mouseX < cx + cw && mouseY >= Math.max(cy, top) && mouseY < Math.min(cy + ch, bottom);
            if (t != null) {
                int[] s = ImageBox.fit(t.w(), t.h(), cw, ch);
                g.fill(cx, cy, cx + cw, cy + ch, RetroUi.HOVER_BG);
                ImageBox.draw(g, this.font, t, false, cx + (cw - s[0]) / 2, cy + (ch - s[1]) / 2, s[0], s[1]);
            } else {
                ImageBox.draw(g, this.font, null, PostImages.thumbFailed(f), cx, cy, cw, ch);
            }
            if (hover) {
                g.renderOutline(cx - 2, cy - 2, cw + 4, ch + 4, RetroUi.ACCENT);
            }
        }
        g.disableScissor();
        int viewport = bottom - top;
        if (contentH() > viewport) {
            int barH = Math.max(10, viewport * viewport / contentH());
            int barY = top + (viewport - barH) * scroll / Math.max(1, contentH() - viewport);
            g.fill(panelX() + panelW() - 4, barY, panelX() + panelW() - 2, barY + barH, RetroUi.GRAY);
        }

        String hint = Component.translatable("feed.shots.hint").getString();
        g.drawString(this.font, hint, x, bottom + 7, RetroUi.GRAY, false);
        String open = Component.translatable("feed.shots.open").getString();
        int ox = panelX() + panelW() - PAD - this.font.width(open);
        openHover = mouseX >= ox && mouseX < ox + this.font.width(open) && mouseY >= bottom + 5 && mouseY < bottom + 17;
        g.drawString(this.font, open, ox, bottom + 7, openHover ? RetroUi.ACCENT : RetroUi.FG, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (openHover) {
            Path dir = folder();
            try {
                Files.createDirectories(dir);
            } catch (IOException ignored) {
                // open whatever exists
            }
            Util.getPlatform().openFile(dir.toFile());
            return true;
        }
        int top = gridTop();
        if (mouseY < top || mouseY >= gridBottom()) {
            return false;
        }
        int x = panelX() + PAD;
        int cw = cellW();
        int ch = cellH();
        int col = (int) ((mouseX - x) / (cw + GAP));
        int row = (int) ((mouseY - top + scroll) / (ch + GAP));
        if (col < 0 || col >= COLS || mouseX - x - col * (cw + GAP) >= cw || (mouseY - top + scroll) - row * (ch + GAP) >= ch) {
            return false;
        }
        int i = row * COLS + col;
        if (i >= 0 && i < files.size()) {
            Path f = files.get(i);
            this.minecraft.setScreen(parent);
            onPick.accept(f);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dy) {
        int max = Math.max(0, contentH() - (gridBottom() - gridTop()));
        scroll = Math.max(0, Math.min(max, scroll - (int) (dy * 24)));
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
