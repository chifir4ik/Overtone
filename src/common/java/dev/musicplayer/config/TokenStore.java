package dev.musicplayer.config;

import dev.musicplayer.Env;
import dev.musicplayer.util.T;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Хранилище токенов в config/musicplayer.json (открытым текстом, но только для владельца — не делись этим файлом).
 * Помимо активного токена хранит список аккаунтов: при выходе токен остаётся в списке.
 */
public final class TokenStore {
    private TokenStore() {}

    private static final String ACCOUNTS = "yandexAccounts";
    private static final int MAX_ACCOUNTS = 12;

    public record Account(String uid, String name, String token) {
        public String displayName() {
            if (name != null && !name.isBlank()) return name;
            return uid != null && !uid.isBlank() ? T.get("account.named", uid) : T.get("account.plain");
        }
    }

    private static Path file() { return Env.configDir().resolve("musicplayer.json"); }

    private static JsonObject read() {
        Path p = file();
        String content;
        try {
            content = Files.readString(p);
        } catch (NoSuchFileException e) {
            return new JsonObject();
        } catch (CharacterCodingException e) {
            return moveCorrupt(p);
        } catch (IOException e) {
            dev.musicplayer.MusicPlayerMod.LOGGER.warn("Cannot read {}: {}", p.getFileName(), e.getMessage());
            return null;
        }
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
        } catch (JsonParseException ignored) {}
        return moveCorrupt(p);
    }

    private static JsonObject moveCorrupt(Path p) {
        Path corrupt = p.resolveSibling(p.getFileName() + ".corrupt");
        try {
            Files.move(p, corrupt, StandardCopyOption.REPLACE_EXISTING);
            restrictToOwner(corrupt);
            return new JsonObject();
        } catch (IOException e) {
            dev.musicplayer.MusicPlayerMod.LOGGER.warn("Cannot move {}: {}", p.getFileName(), e.getMessage());
            return null;
        }
    }

    private static void write(JsonObject o) {
        Path p = file(), temp = null;
        try {
            Files.createDirectories(p.getParent());
            temp = Files.createTempFile(p.getParent(), "musicplayer-", ".tmp");
            restrictToOwner(temp);
            Files.writeString(temp, o.toString());
            try {
                Files.move(temp, p, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            dev.musicplayer.MusicPlayerMod.LOGGER.warn("Cannot write {}: {}", p.getFileName(), e.getMessage());
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); }
                catch (IOException e) { dev.musicplayer.MusicPlayerMod.LOGGER.warn("Cannot remove {}: {}", temp.getFileName(), e.getMessage()); }
            }
        }
    }

    /** На Linux и macOS токены читает только владелец файла; Windows такие права не поддерживает — там это пропускается. */
    private static void restrictToOwner(Path p) {
        try {
            Files.setPosixFilePermissions(p, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | java.io.IOException ignored) {
            // файловая система без POSIX-прав
        }
    }

    public static synchronized String get(String key) {
        JsonObject o = read();
        if (o == null) return null;
        JsonElement value = o.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    public static synchronized void set(String key, String value) {
        JsonObject o = read();
        if (o == null || !writable(readAccounts(o))) return;
        if (value == null) o.remove(key); else o.addProperty(key, value);
        write(o);
    }

    public static synchronized List<Account> accounts() {
        JsonObject o = read();
        return o == null ? List.of() : readAccounts(o).list();
    }

    private record AccountsRead(List<Account> list, boolean valid) {}

    private static AccountsRead readAccounts(JsonObject o) {
        List<Account> out = new ArrayList<>();
        boolean valid = true;
        if (!o.has(ACCOUNTS)) return new AccountsRead(out, true);
        if (!o.get(ACCOUNTS).isJsonArray()) return new AccountsRead(out, false);
        for (JsonElement e : o.getAsJsonArray(ACCOUNTS)) {
            if (!e.isJsonObject()) { valid = false; continue; }
            JsonObject a = e.getAsJsonObject();
            if (!stringField(a, "uid") || !stringField(a, "name") || !stringField(a, "token")) {
                valid = false;
                continue;
            }
            String token = str(a, "token");
            if (token == null || token.isBlank()) continue;
            out.add(new Account(str(a, "uid"), str(a, "name"), token));
        }
        return new AccountsRead(out, valid);
    }

    private static boolean stringField(JsonObject o, String k) {
        return !o.has(k) || (o.get(k).isJsonPrimitive() && o.get(k).getAsJsonPrimitive().isString());
    }

    private static boolean writable(AccountsRead accounts) {
        if (accounts.valid()) return true;
        dev.musicplayer.MusicPlayerMod.LOGGER.warn("Cannot read {}: invalid account structure", file().getFileName());
        return false;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static void writeAccounts(JsonObject o, List<Account> list) {
        JsonArray arr = new JsonArray();
        for (Account a : list) {
            JsonObject j = new JsonObject();
            if (a.uid() != null) j.addProperty("uid", a.uid());
            if (a.name() != null) j.addProperty("name", a.name());
            j.addProperty("token", a.token());
            arr.add(j);
        }
        o.add(ACCOUNTS, arr);
        write(o);
    }

    /** Добавляет аккаунт или обновляет существующий (по uid, а если uid нет — по токену) и делает его первым. */
    public static synchronized void putAccount(String uid, String name, String token) {
        if (token == null || token.isBlank()) return;
        JsonObject o = read();
        if (o == null) return;
        AccountsRead accounts = readAccounts(o);
        if (!writable(accounts)) return;
        List<Account> list = accounts.list();
        list.removeIf(a -> (uid != null && uid.equals(a.uid())) || token.equals(a.token()));
        list.add(0, new Account(uid, name, token));
        while (list.size() > MAX_ACCOUNTS) list.remove(list.size() - 1);
        writeAccounts(o, list);
    }

    public static synchronized void removeAccountByUid(String uid) {
        if (uid == null) return;
        JsonObject o = read();
        if (o == null) return;
        AccountsRead accounts = readAccounts(o);
        if (!writable(accounts)) return;
        List<Account> list = accounts.list();
        if (list.removeIf(a -> uid.equals(a.uid()))) writeAccounts(o, list);
    }

    public static synchronized void removeAccountByToken(String token) {
        if (token == null) return;
        JsonObject o = read();
        if (o == null) return;
        AccountsRead accounts = readAccounts(o);
        if (!writable(accounts)) return;
        List<Account> list = accounts.list();
        if (list.removeIf(a -> token.equals(a.token()))) writeAccounts(o, list);
    }
}
