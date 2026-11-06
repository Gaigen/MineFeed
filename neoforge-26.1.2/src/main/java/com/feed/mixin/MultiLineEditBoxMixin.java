package com.feed.mixin;

import com.feed.client.screen.RetroUi;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Multi-line fields on MineFeed screens: theme colors, no drop shadow (vanilla hardcodes light gray on black). */
@Mixin(MultiLineEditBox.class)
public abstract class MultiLineEditBoxMixin {

    @Redirect(method = {"renderContents", "renderDecorations"}, require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)I"))
    private int feed$string(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        g.text(font, text, x, y, color, !RetroUi.flatFields());
        return 0;
    }

    @Redirect(method = {"renderContents", "renderDecorations"}, require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)I"))
    private int feed$component(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color) {
        g.text(font, text, x, y, color, !RetroUi.flatFields());
        return 0;
    }

    @ModifyConstant(method = "renderContents", require = 0, constant = {@Constant(intValue = -2039584), @Constant(intValue = -3092272)})
    private int feed$text(int color) {
        return RetroUi.flatFields() ? RetroUi.FG : color;
    }

    @ModifyConstant(method = "renderContents", require = 0, constant = @Constant(intValue = -857677600))
    private int feed$placeholder(int color) {
        return RetroUi.flatFields() ? RetroUi.GRAY : color;
    }

    @ModifyConstant(method = "renderDecorations", require = 0, constant = @Constant(intValue = 10526880))
    private int feed$counter(int color) {
        return RetroUi.flatFields() ? RetroUi.GRAY : color;
    }
}
