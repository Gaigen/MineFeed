import com.feed.client.account.AccountApi;
import com.feed.client.account.AccountStore;
import com.feed.client.account.ApiError;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Talks to the DEV worker. Never prints keys or recovery codes. */
public class AccountApiLiveTest {
    static int fails = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "ok   " : "FAIL ") + what);
        if (!ok) fails++;
    }

    static ApiError err(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Exception e) {
            return AccountApi.unwrap(e);
        }
    }

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : AccountApi.DEFAULT_BASE;
        AtomicReference<String> key = new AtomicReference<>();
        AccountApi api = new AccountApi(HttpClient.newHttpClient(), base, "0.2.0-test", key::get);
        String tag = "t_" + Long.toString(System.nanoTime(), 36);

        AccountApi.Meta meta = api.meta().join();
        check(meta.api() == 2 && meta.minClient() != null, "meta api=2 min=" + meta.minClient() + " latest=" + meta.latestClient());
        check(api.checkTag(tag).join().available(), "tag free before register");
        check(!api.checkTag("admin").join().available(), "reserved tag not available");

        AccountApi.Session s = api.register(tag, "Тест §cник").join();
        key.set(s.key());
        check(s.key().startsWith("mfk_") && s.recoveryCode().length() == 24, "register gives key + code");
        check(s.account().tag().equals(tag) && s.account().nick().equals("Тест ник"), "register account fields, nick cleaned");
        System.out.println("     account id=" + s.account().id());
        check(!api.checkTag(tag).join().available(), "tag taken after register");

        ApiError dup = err(() -> api.register(tag, "x").join());
        check(dup != null && "tag_taken".equals(dup.code) && dup.status == 409, "duplicate register -> tag_taken");

        // local store roundtrip
        Path dir = Files.createTempDirectory("mfstore");
        AccountStore store = new AccountStore(dir.resolve("account.json"));
        AccountStore.Data d = new AccountStore.Data();
        d.key = s.key();
        d.account = s.account();
        store.save(d);
        AccountStore.Data back = store.load();
        check(back != null && back.key.equals(s.key()) && back.account.tag().equals(tag), "store save/load");
        check(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("account.json"))).equals("rw-------"), "store file is 600");
        check(!Files.readString(dir.resolve("account.json")).contains(s.recoveryCode()), "store has no recovery code");
        store.clear();
        check(store.load() == null, "store clear");

        check(api.me().join().tag().equals(tag), "me");
        check(api.setNick("Новый ник").join().nick().equals("Новый ник"), "set nick");
        ApiError badNick = err(() -> api.setNick("   ").join());
        check(badNick != null && "bad_nick".equals(badNick.code), "empty nick rejected");

        String tag2 = tag + ".b";
        AccountApi.Account changed = api.setTag(tag2).join();
        check(changed.tag().equals(tag2) && changed.canChangeTagAt() != null, "change tag sets cooldown");
        ApiError cd = err(() -> api.setTag(tag + ".c").join());
        check(cd != null && "tag_cooldown".equals(cd.code) && cd.longExtra("canChangeTagAt") != null, "second change -> cooldown");
        check(!api.checkTag(tag).join().available(), "old tag held");

        String code2 = api.newRecoveryCode().join();
        check(!code2.equals(s.recoveryCode()), "new recovery code differs");
        ApiError oldCode = err(() -> api.recover(s.recoveryCode()).join());
        check(oldCode != null && "bad_code".equals(oldCode.code), "old recovery code rejected");

        String oldKey = key.get();
        AccountApi.Session r = api.recover(code2.toLowerCase().replace("-", " ")).join();
        check(r.account().tag().equals(tag2) && !r.key().equals(oldKey), "recover with code (loose format)");
        ApiError stale = err(() -> api.me().join());
        check(stale != null && stale.isBadKey(), "old key rejected after recover");
        key.set(r.key());
        check(api.me().join().tag().equals(tag2), "new key works");

        api.logout().join();
        ApiError after = err(() -> api.me().join());
        check(after != null && after.isBadKey(), "key rejected after logout");

        AccountApi bad = new AccountApi(HttpClient.newHttpClient(), "https://127.0.0.1:1", "x", () -> null);
        ApiError net = err(() -> bad.meta().join());
        check(net != null && net.isNetwork(), "network error mapped");

        System.out.println("TEST_ACCOUNT_ID=" + s.account().id());
        if (fails > 0) {
            System.out.println(fails + " failures");
            System.exit(1);
        }
        System.out.println("AccountApiLiveTest OK");
    }
}
