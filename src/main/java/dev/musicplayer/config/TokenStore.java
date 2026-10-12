package dev.musicplayer.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Хранилище токенов в config/musicplayer.json (открытым текстом — не делись этим файлом).
 * Помимо активного токена хранит список аккаунтов: при выходе токен остаётся в списке.
 */
public final class TokenStore {
    private TokenStore() {}

    private static final String ACCOUNTS = "yandexAccounts";
    private static final int MAX_ACCOUNTS = 12;

    public record Account(String uid, String name, String token) {
        public String displayName() {
            if (name != null && !name.isBlank()) return name;
            return uid != null && !uid.isBlank() ? "Аккаунт " + uid : "Аккаунт";
        }
    }

    private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("musicplayer.json"); }

    private static JsonObject read() {
        try {
            if (Files.exists(file())) return JsonParser.parseString(Files.readString(file())).getAsJsonObject();
        } catch (Exception ignored) {}
        return new JsonObject();
    }

    private static void write(JsonObject o) {
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), o.toString());
        } catch (Exception ignored) {}
    }

    public static synchronized String get(String key) {
        JsonObject o = read();
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    public static synchronized void set(String key, String value) {
        JsonObject o = read();
        if (value == null) o.remove(key); else o.addProperty(key, value);
        write(o);
    }

    public static synchronized List<Account> accounts() {
        return readAccounts(read());
    }

    private static List<Account> readAccounts(JsonObject o) {
        List<Account> out = new ArrayList<>();
        try {
            if (!o.has(ACCOUNTS) || !o.get(ACCOUNTS).isJsonArray()) return out;
            for (JsonElement e : o.getAsJsonArray(ACCOUNTS)) {
                if (!e.isJsonObject()) continue;
                JsonObject a = e.getAsJsonObject();
                String token = str(a, "token");
                if (token == null || token.isBlank()) continue;
                out.add(new Account(str(a, "uid"), str(a, "name"), token));
            }
        } catch (Exception ignored) {}
        return out;
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
        List<Account> list = readAccounts(o);
        list.removeIf(a -> (uid != null && uid.equals(a.uid())) || token.equals(a.token()));
        list.add(0, new Account(uid, name, token));
        while (list.size() > MAX_ACCOUNTS) list.remove(list.size() - 1);
        writeAccounts(o, list);
    }

    public static synchronized void removeAccountByUid(String uid) {
        if (uid == null) return;
        JsonObject o = read();
        List<Account> list = readAccounts(o);
        if (list.removeIf(a -> uid.equals(a.uid()))) writeAccounts(o, list);
    }

    public static synchronized void removeAccountByToken(String token) {
        if (token == null) return;
        JsonObject o = read();
        List<Account> list = readAccounts(o);
        if (list.removeIf(a -> token.equals(a.token()))) writeAccounts(o, list);
    }
}
