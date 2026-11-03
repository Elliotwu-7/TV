package com.fongmi.android.tv.api.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Converts the website's list files into source entries; never treats its HTML as a TVBox config. */
public final class PortalCatalog {

    public record Entry(String name, List<String> urls, String ua) {
        public Entry {
            urls = List.copyOf(urls);
        }

        public String url() {
            return urls.get(0);
        }
    }

    private PortalCatalog() {
    }

    public static List<Entry> parse(JsonObject config, String text, JsonArray custom, boolean live, String home) {
        List<JsonObject> domains = new ArrayList<>();
        for (JsonElement item : array(config, live ? "liveDomains" : "apiDomains")) {
            if (item.isJsonObject() && isHttp(string(item.getAsJsonObject(), "base"))) domains.add(item.getAsJsonObject());
        }
        // Prefer the exact website requested by the user, then its advertised mirrors.
        domains.sort(Comparator.comparingInt(item -> string(item, "base").startsWith(home) ? 0 : 1));
        JsonObject weights = config.has("apiWeights") && config.get("apiWeights").isJsonObject() ? config.getAsJsonObject("apiWeights") : new JsonObject();
        List<String[]> rows = new ArrayList<>();
        for (String line : text.replace("\uFEFF", "").split("\\R")) {
            String[] fields = line.trim().split("\\|", -1);
            String file = fields[0].trim();
            if (file.isEmpty() || file.startsWith("#") || file.contains("/") || file.contains("\\")) continue;
            if (!(live ? file.endsWith(".txt") || file.endsWith(".m3u") || file.endsWith(".m3u8") : file.endsWith(".json"))) continue;
            rows.add(fields);
        }
        if (!live) rows.sort(Comparator.comparingInt(row -> weight(weights, row[0].trim())));
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (String[] row : rows) {
            String file = row[0].trim();
            String original = field(row, 3);
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            // Live mirrors can be stale; the list's original URL is the preferred live endpoint.
            if (live && isHttp(original)) urls.add(original);
            for (JsonObject domain : domains) {
                String base = string(domain, "base");
                urls.add(resolve(base.endsWith("/") ? base : base + "/", pathSegment(file)));
            }
            if (isHttp(original)) urls.add(original);
            if (urls.isEmpty()) continue;
            String name = live ? file : file.substring(0, file.length() - 5);
            if (live && !field(row, 4).isEmpty()) name = field(row, 4) + " · " + file;
            Entry entry = new Entry(name, new ArrayList<>(urls), live ? field(row, 5) : "");
            entries.putIfAbsent(entry.url(), entry);
        }
        if (!live) for (JsonElement element : custom) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String name = string(item, "name");
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            for (JsonElement link : array(item, "urls")) {
                if (link.isJsonObject() && isHttp(string(link.getAsJsonObject(), "url"))) urls.add(string(link.getAsJsonObject(), "url"));
            }
            if (name.isEmpty() || urls.isEmpty()) continue;
            Entry entry = new Entry(name, new ArrayList<>(urls), "");
            entries.putIfAbsent(entry.url(), entry);
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("网站没有返回有效的" + (live ? "直播" : "点播") + "源清单");
        return List.copyOf(entries.values());
    }

    public static JsonArray encode(List<Entry> entries) {
        JsonArray result = new JsonArray();
        for (Entry entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("name", entry.name());
            item.addProperty("ua", entry.ua());
            JsonArray urls = new JsonArray();
            entry.urls().forEach(urls::add);
            item.add("urls", urls);
            result.add(item);
        }
        return result;
    }

    public static List<Entry> decode(String text) {
        List<Entry> result = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(text).getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            LinkedHashSet<String> urls = new LinkedHashSet<>();
            for (JsonElement url : array(item, "urls")) if (url.isJsonPrimitive() && isHttp(url.getAsString())) urls.add(url.getAsString());
            if (!string(item, "name").isEmpty() && !urls.isEmpty()) result.add(new Entry(string(item, "name"), new ArrayList<>(urls), string(item, "ua")));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("本地源清单为空");
        return List.copyOf(result);
    }

    public static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString().trim() : "";
    }

    public static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    public static String resolve(String base, String path) {
        String url = URI.create(base).resolve(path).toString();
        if (!isHttp(url)) throw new IllegalArgumentException("源地址不是 HTTP/HTTPS");
        return url;
    }

    private static String pathSegment(String value) {
        try {
            return new URI(null, null, value, null).toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static boolean isHttp(String value) {
        try {
            URI uri = URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme())) && uri.getRawAuthority() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String field(String[] fields, int index) {
        String value = index < fields.length ? fields[index].trim() : "";
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    private static int weight(JsonObject weights, String name) {
        try {
            return weights.has(name) ? weights.get(name).getAsInt() : 999;
        } catch (RuntimeException e) {
            return 999;
        }
    }
}
