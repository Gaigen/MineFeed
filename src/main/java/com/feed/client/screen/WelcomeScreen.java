package com.feed.client.screen;

import com.feed.client.account.AccountManager;
import com.feed.client.account.TagRules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** First launch: pick a nick and a unique @tag, or sign in with a recovery code. */
@OnlyIn(Dist.CLIENT)
public class WelcomeScreen extends AccountPanelScreen {

    private final Screen next;
    private final boolean sessionLost;
    private final TagChecker checker = new TagChecker(null);
    private String nickValue;
    private String tagValue;
    private EditBox nick;
    private EditBox tag;
    private RetroButton create;
    private String intro;
    private int introY;
    private int nickLabelY;
    private int tagLabelY;
    private int tagStatusY;
    private int statusY;

    public WelcomeScreen(Screen next) {
        super(Component.translatable("feed.account.welcome.title"));
        this.next = next;
        this.sessionLost = AccountManager.INSTANCE.consumeSessionLost();
    }

    @Override
    protected void init() {
        if (nickValue == null) {
            String mc = this.minecraft.getUser().getName();
            nickValue = mc;
            tagValue = TagRules.suggestFrom(mc);
        }
        int w = panelWidth() - PAD * 2;
        intro = Component.translatable(sessionLost ? "feed.account.welcome.session_lost"
                : "feed.account.welcome.intro").getString();
        int introH = AccountUi.wrappedHeight(this.font, intro, w);
        int y = layoutPanel(introH + 6 + LINE + AccountUi.FIELD_H + 6 + LINE + AccountUi.FIELD_H
                + 3 + LINE + 2 + LINE + 4 + BTN_H + 6 + BTN_H);
        introY = y;
        y += introH + 6;

        nickLabelY = y;
        y += LINE;
        nick = addField(AccountUi.field(this.font, innerX(), y, innerW(), TagRules.NICK_MAX,
                Component.translatable("feed.account.nick.hint")));
        nick.setValue(nickValue);
        nick.setResponder(s -> nickValue = s);
        y += AccountUi.FIELD_H + 6;

        tagLabelY = y;
        y += LINE;
        tag = addField(AccountUi.tagField(this.font, innerX(), y, innerW(),
                Component.translatable("feed.account.tag.hint")));
        tag.setResponder(s -> {
            tagValue = s;
            checker.onChanged(s);
        });
        tag.setValue(tagValue);
        if (!tagValue.equals(checker.value())) {
            checker.onChanged(tagValue);
        }
        y += AccountUi.FIELD_H + 3;
        tagStatusY = y;
        y += LINE + 2;
        statusY = y;
        y += LINE + 4;

        create = addRenderableWidget(new RetroButton(innerX(), y, innerW(), BTN_H,
                Component.translatable("feed.account.welcome.create"), b -> submit()));
        y += BTN_H + 6;
        addRenderableWidget(new RetroButton(innerX(), y, innerW(), BTN_H,
                Component.translatable("feed.account.welcome.have_code"),
                b -> this.minecraft.setScreen(new RecoverScreen(this, next))));
        setInitialFocus(tagValue.isEmpty() ? tag : nick);
    }

    private void submit() {
        if (busy) {
            return;
        }
        String cleanNick = TagRules.cleanNick(nickValue);
        if (!TagRules.validNick(cleanNick)) {
            setStatus(Component.translatable("feed.account.error.bad_nick"), RetroUi.ERROR);
            setFocused(nick);
            return;
        }
        if (checker.state() == TagChecker.State.EMPTY) {
            setStatus(Component.translatable("feed.account.tag.length"), RetroUi.ERROR);
            setFocused(tag);
            return;
        }
        if (!checker.submittable()) {
            setStatus(checker.message(), RetroUi.ERROR);
            setFocused(tag);
            return;
        }
        busy = true;
        setStatus(null, RetroUi.GRAY);
        AccountManager.thenOnMain(AccountManager.INSTANCE.api().register(checker.value(), cleanNick), s -> {
            busy = false;
            AccountManager.INSTANCE.signIn(s);
            this.minecraft.setScreen(new RecoveryCodeScreen(next, s.recoveryCode(), true));
        }, err -> {
            busy = false;
            if ("tag_taken".equals(err.code)) {
                checker.markTaken();
            }
            setStatus(AccountUi.describe(err), RetroUi.ERROR);
        });
    }

    @Override
    public void tick() {
        super.tick();
        checker.tick();
        if (create != null) {
            create.active = !busy;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isEnter(keyCode) && (getFocused() == nick || getFocused() == tag)) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        AccountUi.drawWrapped(g, this.font, intro, innerX(), introY, innerW(),
                sessionLost ? RetroUi.ACCENT : RetroUi.FG);
        AccountUi.drawLabel(g, this.font, Component.translatable("feed.account.nick.label"), innerX(), nickLabelY);
        AccountUi.drawLabel(g, this.font, Component.translatable("feed.account.tag.label"), innerX(), tagLabelY);
        g.drawString(this.font, checker.message(), innerX(), tagStatusY, checker.color(), false);
        drawStatus(g, statusY);
    }
}
