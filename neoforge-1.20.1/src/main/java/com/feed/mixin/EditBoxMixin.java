package com.feed.mixin;

import com.feed.client.screen.RetroUi;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Text fields on MineFeed screens: flat text without the vanilla drop shadow (it smears on the light theme)
 * and a cursor in the theme color. Other screens are left alone.
 */
@Mixin(EditBox.class)
public abstract class EditBoxMixin {

    @ModifyArg(method = "renderWidget", require = 0, index = 5, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)I"))
    private boolean feed$shadow_seq(boolean shadow) {
        return shadow && !RetroUi.flatFields();
    }

    @ModifyArg(method = "renderWidget", require = 0, index = 5, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)I"))
    private boolean feed$shadow_component(boolean shadow) {
        return shadow && !RetroUi.flatFields();
    }

    @ModifyArg(method = "renderWidget", require = 0, index = 5, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)I"))
    private boolean feed$shadow_string(boolean shadow) {
        return shadow && !RetroUi.flatFields();
    }

    @ModifyConstant(method = "renderWidget", require = 0, constant = @Constant(intValue = -3092272))
    private int feed$cursor(int color) {
        return RetroUi.flatFields() ? RetroUi.FG : color;
    }
}
