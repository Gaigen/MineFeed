package com.feed.mixin;
import com.feed.client.NotificationPoller;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(MouseHandler.class)
public abstract class FabricMouseMixin {
    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void feed$onPress(long window, int button, int action, int mods, CallbackInfo ci) {
        if (NotificationPoller.onMouseClick(button, action)) ci.cancel();
    }
}
