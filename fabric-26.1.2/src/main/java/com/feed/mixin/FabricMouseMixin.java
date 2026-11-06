package com.feed.mixin;
import com.feed.client.NotificationPoller;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MouseHandler.class)
public abstract class FabricMouseMixin {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void feed$onPress(long window, net.minecraft.client.input.MouseButtonInfo info, int action, CallbackInfo ci) {
        if (NotificationPoller.onMouseClick(info.button(), action)) ci.cancel();
    }
}
