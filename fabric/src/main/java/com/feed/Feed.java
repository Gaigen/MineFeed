package com.feed;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
public final class Feed {
    public static final String MOD_ID = "feed";
    public static final Logger LOG = LoggerFactory.getLogger("Feed");
    public static String clientVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("0.0.0");
    }
    private Feed() {}
}
