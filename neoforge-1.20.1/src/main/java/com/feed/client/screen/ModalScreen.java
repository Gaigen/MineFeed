package com.feed.client.screen;

import com.feed.client.FeedUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Dialog drawn over the dimmed feed screen, with a ✕ in the corner and hand-drawn buttons. */
public abstract class ModalScreen extends RetroScreen {

    protected static final int PAD = 10;
    protected static final int BTN_H = 16;

    protected final FeedScreen parent;
    /** Screen drawn behind the dialog and returned to on close (the page it was opened from). */
    private net.minecraft.client.gui.screens.Screen back;
    protected int px;
    protected int py;
    protected int pw;
    protected int ph;
    protected Component status = Component.empty();
    protected int statusColor = RetroUi.ERROR;
    protected boolean busy;
    protected final List<EditBox> fields = new ArrayList<>();
    private final List<Btn> buttons = new ArrayList<>();
    private boolean backdropFailed;

    /** A hand-drawn button registered during render; clicks are matched against the last frame. */
    protected record Btn(int x, int y, int w, int h, Runnable action) {}

    protected ModalScreen(Component title, FeedScreen parent) {
        super(title);
        this.parent = parent;
        this.back = parent;
    }

    /** Opened from another page (not the feed): show it behind and return there. */
    public ModalScreen from(net.minecraft.client.gui.screens.Screen screen) {
        if (screen != null) {
            this.back = screen;
        }
        return this;
    }

    /** Call at the start of init(). */
    protected void layout(int w, int h) {
        fields.clear();
        pw = Math.min(w, this.width - 16);
        ph = Math.min(h, this.height - 16);
        px = (this.width - pw) / 2;
        py = Math.max(8, (this.height - ph) / 2);
    }

    protected int innerX() {
        return px + PAD;
    }

    protected int innerW() {
        return pw - PAD * 2;
    }

    protected int bodyTop() {
        return py + PAD + 16;
    }

    protected EditBox addField(EditBox box) {
        fields.add(box);
        return addRenderableWidget(box);
    }

    protected void setStatus(Component text, int color) {
        status = text == null ? Component.empty() : text;
        statusColor = color;
    }

    protected void drawStatus(GuiGraphics g, int y) {
        if (busy) {
            g.drawString(this.font, Component.translatable("feed.account.working").getString()
                    + (RetroUi.blink() ? "_" : ""), innerX(), y, RetroUi.GRAY, false);
        } else if (!status.getString().isEmpty()) {
            AccountUi.drawWrapped(g, this.font, status.getString(), innerX(), y, innerW(), statusColor);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        if (back != null && !backdropFailed && back.width == this.width && back.height == this.height) {
            try {
                back.render(g, -1, -1, net.minecraft.client.Minecraft.getInstance().getFrameTime());
            } catch (RuntimeException e) {
                backdropFailed = true;
            }
            // Text is batched until the end of the frame, and shadowed text sits slightly above z=0:
            // draw the feed now and forget its depth, so the dialog covers it completely.
            g.flush();
            com.mojang.blaze3d.systems.RenderSystem.clear(org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT,
                    net.minecraft.client.Minecraft.ON_OSX);
        } else {
            g.fill(0, 0, this.width, this.height, RetroUi.PAGE);
        }
        g.fill(0, 0, this.width, this.height, RetroUi.DIM);
        g.fill(px + RetroUi.SHADOW_OFFSET, py + RetroUi.SHADOW_OFFSET, px + pw + RetroUi.SHADOW_OFFSET,
                py + ph + RetroUi.SHADOW_OFFSET, RetroUi.SHADOW);
        g.fill(px, py, px + pw, py + ph, RetroUi.BG);
        g.renderOutline(px, py, pw, ph, RetroUi.FG);
        g.drawString(this.font, FeedUtil.clip(this.font, getTitle().getString(), pw - PAD * 2 - 20),
                innerX(), py + PAD, RetroUi.ACCENT, false);
        for (EditBox f : fields) {
            if (f.isVisible()) {
                AccountUi.drawField(g, f);
            }
        }
    }

    /** Window resized: re-layout the feed behind too, so the backdrop keeps matching. */
    @Override
    public void resize(net.minecraft.client.Minecraft mc, int w, int h) {
        if (back != null) {
            back.resize(mc, w, h);
        }
        super.resize(mc, w, h);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        buttons.clear();
        super.render(g, mouseX, mouseY, partialTick);
        button(g, "\u2715", px + pw - PAD - 12, py + 6, 14, 14, RetroUi.FG, false, mouseX, mouseY, this::onClose);
        renderContent(g, mouseX, mouseY);
    }

    protected abstract void renderContent(GuiGraphics g, int mouseX, int mouseY);

    /** Draws a button and registers it for clicks. w <= 0 = fit the label. Returns its width. */
    protected int button(GuiGraphics g, String label, int x, int y, int w, int h, int color, boolean filled,
                         int mouseX, int mouseY, Runnable action) {
        if (w <= 0) {
            w = buttonWidth(label);
        }
        boolean enabled = action != null && !busy;
        boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int c = enabled ? color : RetroUi.GRAY;
        g.fill(x + 2, y + 2, x + w + 2, y + h + 2, RetroUi.SHADOW);
        boolean inv = filled || hover;
        g.fill(x, y, x + w, y + h, inv ? c : RetroUi.BG);
        g.renderOutline(x, y, w, h, c);
        g.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2, inv ? RetroUi.BG : c, false);
        if (enabled) {
            buttons.add(new Btn(x, y, w, h, action));
        }
        return w;
    }

    /** Registers a custom-drawn clickable area for this frame. */
    protected void hit(int x, int y, int w, int h, Runnable action) {
        if (!busy) {
            buttons.add(new Btn(x, y, w, h, action));
        }
    }

    protected int buttonWidth(String label) {
        return this.font.width(label) + 12;
    }

    /** Toggle chip, registered for clicks. Returns its width. */
    protected int chip(GuiGraphics g, String label, int x, int y, int color, boolean on, Runnable action) {
        RetroUi.drawChip(g, this.font, label, x, y, color, on);
        int w = RetroUi.chipWidth(this.font, label);
        if (action != null && !busy) {
            buttons.add(new Btn(x, y, w, RetroUi.CHIP_H, action));
        }
        return w;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        for (Btn b : new ArrayList<>(buttons)) {
            if (mouseX >= b.x() && mouseX < b.x() + b.w() && mouseY >= b.y() && mouseY < b.y() + b.h()) {
                b.action().run();
                return true;
            }
        }
        if (mouseX < px || mouseX >= px + pw || mouseY < py || mouseY >= py + ph) {
            onClose(); // click outside the dialog
            return true;
        }
        return false;
    }

    protected boolean isEnter(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER;
    }

    protected String tr(String key, Object... args) {
        return Component.translatable(key, args).getString();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(back);
    }
}
