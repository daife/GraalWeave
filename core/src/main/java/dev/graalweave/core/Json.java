package dev.graalweave.core;

import com.google.gson.*;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private Json() {}
    public static JsonObject object(Object... pairs) {
        var result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.add((String) pairs[i], GSON.toJsonTree(pairs[i + 1]));
        return result;
    }
    public static String string(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }
}
