package com.feed.client.screen;

import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Locale;

/** Sign in on this PC with a recovery code. Signs out every other PC and issues a new code. */
@OnlyIn(Dist.CLIENT)
public class RecoverScreen extends AccountPanelScreen {

    private static final int CODE_CHARS = 20;

    private final Screen back;
    private final Screen next;
    private String codeValue = "";
    private EditBox code;
    private RetroButton signIn;
    private String intro;
    private int introY;
    private int labelY;
    private int statusY;

    public RecoverScreen(Screen back, Screen next) {
        super(Component.translatable("feed.account.recover.title"));
        this.back = back;
        this.next = next;
    }

    @Override
    protected void init() {
        int w = panelWidth() - PAD * 2;
        AccountApi.Account current = AccountManager.INSTANCE.account();
        intro = Component.translatable("feed.account.recover.intro").getString();
        if (current != null) {
            intro += "\n" + Component.translatable("feed.account.recover.replace", "@" + current.tag()).getString();
        }
        int introH = AccountUi.wrappedHeight(this.font, intro, w);
        int y = layoutPanel(introH + 6 + LINE + AccountUi.FIELD_H + 4 + LINE + 4 + BTN_H);
        introY = y;
        y += introH + 6;
        labelY = y;
        y += LINE;
        code = addField(new EditBox(this.font, innerX() + 4, y + 4, innerW() - 8, AccountUi.FIELD_H - 6,
                Component.translatable("feed.account.recover.hint")) {
            @Override
            public void insertText(String text) {
                super.insertText(text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9-]", ""));
            }
        });
        code.setBordered(false);
        code.setMaxLength(32);
        code.setTextColor(RetroUi.ACCENT);
        code.setHint(Component.literal("XXXX-XXXX-XXXX-XXXX-XXXX").withColor(RetroUi.GRAY & 0xFFFFFF));
        code.setValue(codeValue);
        code.setResponder(s -> codeValue = s);
        y += AccountUi.FIELD_H + 4;
        statusY = y;
        y += LINE + 4;
        int bw = buttonsRowWidth(2, 8);
        signIn = addRenderableWidget(new RetroButton(innerX(), y, bw, BTN_H,
                Component.translatable("feed.account.recover.sign_in"), b -> submit()));
        addRenderableWidget(new RetroButton(innerX() + bw + 8, y, bw, BTN_H,
                Component.translatable("feed.account.back"), b -> onClose()));
        setInitialFocus(code);
    }

    private void submit() {
        if (busy) {
            return;
        }
        String raw = codeValue.replaceAll("[^A-Z0-9]", "");
        if (raw.length() != CODE_CHARS) {
            setStatus(Component.translatable("feed.account.recover.length"), RetroUi.ERROR);
            return;
        }
        busy = true;
        setStatus(null, RetroUi.GRAY);
        AccountManager.thenOnMain(AccountManager.INSTANCE.api().recover(codeValue.trim()), s -> {
            busy = false;
            AccountManager.INSTANCE.signIn(s);
            codeValue = "";
            this.minecraft.setScreen(new RecoveryCodeScreen(next, s.recoveryCode(), false));
        }, err -> {
            busy = false;
            setStatus(AccountUi.describe(err), RetroUi.ERROR);
        });
    }

    @Override
    public void tick() {
        super.tick();
        if (signIn != null) {
            signIn.active = !busy;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isEnter(keyCode) && getFocused() == code) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        AccountUi.drawWrapped(g, this.font, intro, innerX(), introY, innerW(), RetroUi.FG);
        AccountUi.drawLabel(g, this.font, Component.translatable("feed.account.recover.label"), innerX(), labelY);
        drawStatus(g, statusY);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(back);
    }
}
