package com.feed.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class ModKeyMappings {

    public static final net.minecraft.client.KeyMapping OPEN_FEED =
            new net.minecraft.client.KeyMapping(
                    "key.feed.open",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_J,
                    "key.categories.feed"
            );

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_FEED);
    }

    private ModKeyMappings() {
    }
}
