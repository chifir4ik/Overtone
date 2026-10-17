package dev.musicplayer.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Безопасное чтение чужого JSON: отсутствующее поле даёт пустое значение, а не исключение. */
public final class Json {
    private Json() {}

    public static JsonObject obj(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonObject() ? o.getAsJsonObject(key) : new JsonObject();
    }

    public static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    public static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }
}
