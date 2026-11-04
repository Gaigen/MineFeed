package com.feed.client.screen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Yes / cancel dialog in the retro style. */
@OnlyIn(Dist.CLIENT)
public class RetroConfirmScreen extends AccountPanelScreen {

    private final Screen parent;
    private final Component message;
    private final Component yesLabel;
    private final boolean danger;
    private final Runnable onYes;
    private int textY;

    public RetroConfirmScreen(Screen parent, Component title, Component message, Component yesLabel,
                              boolean danger, Runnable onYes) {
        super(title);
        this.parent = parent;
        this.message = message;
        this.yesLabel = yesLabel;
        this.danger = danger;
        this.onYes = onYes;
    }

    @Override
    protected void init() {
        int textH = AccountUi.wrappedHeight(this.font, message.getString(), panelWidth() - PAD * 2);
        int y = layoutPanel(textH + 8 + BTN_H);
        textY = y;
        y += textH + 8;
        int bw = buttonsRowWidth(2, 8);
        addRenderableWidget(new RetroButton(innerX(), y, bw, BTN_H, yesLabel, b -> onYes.run()));
        addRenderableWidget(new RetroButton(innerX() + bw + 8, y, bw, BTN_H,
                Component.translatable("feed.account.cancel"), b -> onClose()));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        AccountUi.drawWrapped(g, this.font, message.getString(), innerX(), textY, innerW(),
                danger ? RetroUi.ERROR : RetroUi.FG);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
