package com.feed.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Shows a freshly issued recovery code once. The code is never written to disk or logs. */
@OnlyIn(Dist.CLIENT)
public class RecoveryCodeScreen extends AccountPanelScreen {

    private static final long DONE_DELAY_MS = 2500;
    private static final int CODE_BOX_H = 22;

    private final Screen next;
    private final String code;
    private final boolean firstTime;
    private final long shownAt = System.currentTimeMillis();
    private boolean copied;
    private RetroButton copyButton;
    private RetroButton doneButton;
    private int introY;
    private int codeY;
    private int warnY;
    private String intro;
    private String warn;

    public RecoveryCodeScreen(Screen next, String code, boolean firstTime) {
        super(Component.translatable("feed.account.recovery.title"));
        this.next = next;
        this.code = code;
        this.firstTime = firstTime;
    }

    @Override
    protected void init() {
        int w = panelWidth() - PAD * 2;
        intro = Component.translatable(firstTime ? "feed.account.recovery.intro_new" : "feed.account.recovery.intro").getString();
        warn = Component.translatable("feed.account.recovery.warn").getString();
        int introH = AccountUi.wrappedHeight(this.font, intro, w);
        int warnH = AccountUi.wrappedHeight(this.font, warn, w);
        int y = layoutPanel(introH + 6 + CODE_BOX_H + 6 + warnH + 8 + BTN_H);
        introY = y;
        y += introH + 6;
        codeY = y;
        y += CODE_BOX_H + 6;
        warnY = y;
        y += warnH + 8;
        int bw = buttonsRowWidth(2, 8);
        copyButton = addRenderableWidget(new RetroButton(innerX(), y, bw, BTN_H, copyLabel(), b -> {
            this.minecraft.keyboardHandler.setClipboard(code);
            copied = true;
            b.setMessage(copyLabel());
        }));
        doneButton = addRenderableWidget(new RetroButton(innerX() + bw + 8, y, bw, BTN_H,
                Component.translatable("feed.account.recovery.done"), b -> onClose()));
        doneButton.active = doneReady();
    }

    private Component copyLabel() {
        return Component.translatable(copied ? "feed.account.recovery.copied" : "feed.account.recovery.copy");
    }

    private boolean doneReady() {
        return copied || System.currentTimeMillis() - shownAt >= DONE_DELAY_MS;
    }

    @Override
    public void tick() {
        super.tick();
        if (doneButton != null) {
            doneButton.active = doneReady();
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        AccountUi.drawWrapped(g, this.font, intro, innerX(), introY, innerW(), RetroUi.FG);
        g.fill(innerX(), codeY, innerX() + innerW(), codeY + CODE_BOX_H, RetroUi.HOVER_BG);
        g.renderOutline(innerX(), codeY, innerW(), CODE_BOX_H, RetroUi.ACCENT);
        int cx = innerX() + (innerW() - this.font.width(code)) / 2;
        g.drawString(this.font, code, cx, codeY + (CODE_BOX_H - 8) / 2, RetroUi.ACCENT, false);
        AccountUi.drawWrapped(g, this.font, warn, innerX(), warnY, innerW(), RetroUi.GRAY);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return doneReady();
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(next);
    }
}
