package com.feed.client.account;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Local account file. Lives in the user's home ({@code ~/.minefeed/account.json}), not in the
 * instance config, so modpacks/shared configs never carry someone's key and all instances on
 * this PC share one account. The recovery code is never stored.
 */
public final class AccountStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Data {
        public int version = 1;
        public String key;
        public AccountApi.Account account;
    }

    private final Path file;

    public AccountStore(Path file) {
        this.file = file;
    }

    public static Path defaultFile(Path gameDirFallback) {
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            Path dir = Path.of(home, ".minefeed");
            try {
                Files.createDirectories(dir);
                if (Files.isWritable(dir)) {
                    return dir.resolve("account.json");
                }
            } catch (IOException | SecurityException ignored) {
                // fall through
            }
        }
        return gameDirFallback.resolve("minefeed").resolve("account.json");
    }

    public Path file() {
        return file;
    }

    public Data load() throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        Data d = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
        if (d == null || d.key == null || !d.key.startsWith("mfk_")) {
            return null;
        }
        return d;
    }

    public void save(Data d) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(d), StandardCharsets.UTF_8);
        restrict(tmp);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        restrict(file);
    }

    public void clear() throws IOException {
        Files.deleteIfExists(file);
    }

    private static void restrict(Path p) {
        try {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException | SecurityException ignored) {
            // Windows: user profile folder is already private to the user.
        }
    }
}
