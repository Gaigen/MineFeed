package com.feed.client;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
public final class ModKeyMappings {
    public static final KeyMapping OPEN_FEED = new KeyMapping("key.feed.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath("feed", "general")));
    private ModKeyMappings() {}
}
