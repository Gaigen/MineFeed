package com.feed.client.account;

import com.feed.Feed;
import com.feed.client.FeedHttp;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Holds the signed-in MineFeed account for this PC. All callbacks given to {@code thenOnMain}
 * run on the Minecraft client thread.
 */
public final class AccountManager {

    public static final AccountManager INSTANCE = new AccountManager();

    private final AccountStore store;
    private final AccountApi api;
    private volatile AccountStore.Data data;
    private volatile boolean loaded;
    private volatile boolean refreshedThisSession;
    /** Set when the server rejected our key (e.g. someone used the recovery code on another PC). */
    private volatile boolean sessionLost;

    private AccountManager() {
        this.store = new AccountStore(AccountStore.defaultFile(FMLPaths.GAMEDIR.get()));
        this.api = new AccountApi(FeedHttp.CLIENT, AccountApi.baseFromEnvironment(), clientVersion(), this::key);
        AccountApi.language = () -> {
            try {
                return net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected();
            } catch (RuntimeException e) {
                return null;
            }
        };
    }

    public static String clientVersion() {
        return ModList.get().getModContainerById(Feed.MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("0.0.0");
    }

    public AccountApi api() {
        return api;
    }

    private synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        try {
            data = store.load();
        } catch (Exception e) {
            Feed.LOG.warn("MineFeed: could not read account file ({})", e.getClass().getSimpleName());
            data = null;
        }
    }

    private String key() {
        ensureLoaded();
        AccountStore.Data d = data;
        return d == null ? null : d.key;
    }

    public boolean hasAccount() {
        ensureLoaded();
        return data != null;
    }

    public AccountApi.Account account() {
        ensureLoaded();
        AccountStore.Data d = data;
        return d == null ? null : d.account;
    }

    public boolean consumeSessionLost() {
        boolean v = sessionLost;
        sessionLost = false;
        return v;
    }

    /** Stores a fresh session (after register / recover). */
    public void signIn(AccountApi.Session s) {
        AccountStore.Data d = new AccountStore.Data();
        d.key = s.key();
        d.account = s.account();
        data = d;
        loaded = true;
        refreshedThisSession = true;
        sessionLost = false;
        persist();
        syncSkin(s.account());
    }

    /**
     * Tells the server which Minecraft skin is ours (only for a real Microsoft login:
     * offline profiles have made-up UUIDs). Other players then see it as the avatar.
     */
    private void syncSkin(AccountApi.Account acc) {
        net.minecraft.client.User user = Minecraft.getInstance().getUser();
        if (acc == null || user.getType() != net.minecraft.client.User.Type.MSA || user.getProfileId() == null) {
            return;
        }
        String uuid = user.getProfileId().toString().replace("-", "");
        if (uuid.equals(acc.skin())) {
            return;
        }
        thenOnMain(api.setSkin(uuid), this::updateAccount, e -> {});
    }

    public void updateAccount(AccountApi.Account a) {
        AccountStore.Data d = data;
        if (d == null || a == null) {
            return;
        }
        d.account = a;
        persist();
    }

    /** Forget the local key (after logout or when the server rejected it). */
    public void signOutLocal() {
        data = null;
        loaded = true;
        try {
            store.clear();
        } catch (Exception e) {
            Feed.LOG.warn("MineFeed: could not delete account file ({})", e.getClass().getSimpleName());
        }
    }

    /** Refresh /v2/me once per game session; network errors keep the cached profile. */
    public void refreshOnce() {
        if (refreshedThisSession || !hasAccount()) {
            return;
        }
        refreshedThisSession = true;
        api.me().whenComplete((acc, ex) -> Minecraft.getInstance().execute(() -> {
            if (ex == null) {
                updateAccount(acc);
                syncSkin(acc);
                if (acc.lostTag() != null) {
                    net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(
                            Minecraft.getInstance().getToasts(),
                            net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                            net.minecraft.network.chat.Component.translatable("feed.account.lost_tag.toast_title"),
                            net.minecraft.network.chat.Component.translatable("feed.account.lost_tag.toast", "@" + acc.lostTag()));
                }
                return;
            }
            handleError(AccountApi.unwrap(ex));
        }));
    }

    /** Common reaction to API errors for authenticated calls. Returns true if the key was dropped. */
    public boolean handleError(ApiError e) {
        if (e.isBadKey() && hasAccount()) {
            signOutLocal();
            sessionLost = true;
            return true;
        }
        return false;
    }

    private void persist() {
        AccountStore.Data d = data;
        if (d == null) {
            return;
        }
        try {
            store.save(d);
        } catch (Exception e) {
            Feed.LOG.warn("MineFeed: could not save account file ({})", e.getClass().getSimpleName());
        }
    }

    /** Runs the callback on the client thread with either the result or an unwrapped error. */
    public static <T> void thenOnMain(CompletableFuture<T> f, Consumer<T> ok, Consumer<ApiError> fail) {
        f.whenComplete((v, ex) -> Minecraft.getInstance().execute(() -> {
            if (ex == null) {
                ok.accept(v);
            } else {
                ApiError err = AccountApi.unwrap(ex);
                INSTANCE.handleError(err);
                fail.accept(err);
            }
        }));
    }
}
