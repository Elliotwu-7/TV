package com.fongmi.android.tv.api.config;

import android.util.Log;
import android.util.AtomicFile;
import android.os.Build;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.fongmi.android.tv.api.Decoder;
import com.github.catvod.utils.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

/** Startup sync and last-known catalog for Z10 BOX. User-selected sources remain selected. */
public final class PortalSources {

    public static final String HOME = "https://0.12yue.de5.net/";
    public static final String LIVE_HOME = HOME + "#live";
    private static final String TAG = "Z10Sources";
    private static final long CATALOG_TTL = TimeUnit.HOURS.toMillis(1);
    private static final Object[] LOCKS = {new Object(), new Object()};
    private static final List<?>[] CATALOGS = new List<?>[2];
    private static final long[] LOADED = new long[2];
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();
    private static JsonObject site;
    private static long siteLoaded;

    private PortalSources() {
    }

    public static boolean isHome(String url) {
        return HOME.equals(url) || LIVE_HOME.equals(url) || HOME.substring(0, HOME.length() - 1).equals(url);
    }

    public static boolean isX86() {
        for (String abi : Build.SUPPORTED_ABIS) if ("x86".equals(abi) || "x86_64".equals(abi)) return true;
        return false;
    }

    public static String vod(Config config, String tag) throws Exception {
        String cached = cachedConfig(config.getJson());
        if (!cached.isEmpty()) {
            refreshInBackground(config, tag);
            return cached;
        }
        if (isHome(config.getUrl())) {
            String bundled = bundledConfig();
            if (!bundled.isEmpty()) {
                config.json(bundled).save();
                refreshInBackground(config, tag);
                return bundled;
            }
        }
        return loadNetwork(config, tag, true);
    }

    private static String loadNetwork(Config config, String tag, boolean notifyCached) throws Exception {
        if (!isHome(config.getUrl()) && !knownSource(config.getUrl())) {
            String json = Decoder.getJson(UrlUtil.convert(config.getUrl()), tag);
            json = normalize(config.getUrl(), json, tag);
            config.json(json).save();
            return json;
        }
        List<PortalCatalog.Entry> entries = catalog(0, tag, false);
        register(entries, 0);
        Exception failure = null;
        List<PortalCatalog.Entry> candidates = isHome(config.getUrl()) ? entries : entries.stream().filter(item -> item.urls().contains(config.getUrl())).toList();
        if (candidates.isEmpty()) {
            String json = normalize(config.getUrl(), Decoder.getJson(UrlUtil.convert(config.getUrl()), tag), tag);
            config.json(json).save();
            return json;
        }
        for (PortalCatalog.Entry entry : candidates) for (String url : entry.urls()) {
            try {
                String json = normalize(url, Decoder.getJson(url, tag, 12000), tag);
                config.json(json).save();
                return json;
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) throw e;
                failure = e;
                Log.w(TAG, "Source endpoint failed: " + url, e);
                // Mirrors of one list entry normally point to the same JSON and spider.
                // Once that JSON is readable but its root JAR is invalid, move to the next entry.
                if (e.getMessage() != null && e.getMessage().contains("spider JAR")) break;
            }
        }
        String cached = cachedConfig(config.getJson());
        if (!cached.isEmpty()) {
            config.json(cached).save();
            if (notifyCached) Notify.show(R.string.z10_sources_cached);
            return cached;
        }
        throw new IOException("点播源暂不可用，请在设置中切换数据源", failure);
    }

    private static void refreshInBackground(Config config, String tag) {
        if (!REFRESHING.compareAndSet(false, true)) return;
        Task.submit(() -> {
            try {
                if (isHome(config.getUrl())) catalog(0, tag, true);
                else loadNetwork(config, tag, false);
            } catch (Throwable e) {
                if (!Thread.currentThread().isInterrupted()) Log.w(TAG, "Background source refresh failed", e);
            } finally {
                REFRESHING.set(false);
            }
        });
    }

    private static String bundledConfig() {
        try (InputStream stream = App.get().getAssets().open("z10/bootstrap.json")) {
            return cachedConfig(Path.read(stream));
        } catch (Exception e) {
            Log.w(TAG, "Bundled source config unavailable", e);
            return "";
        }
    }

    private static String normalize(String source, String json, String tag) throws Exception {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        if (PortalCatalog.array(object, "sites").isEmpty() && PortalCatalog.array(object, "urls").isEmpty()) throw new IOException("该线路未返回 TVBox 数据源");
        if (!usableConfig(source, object, tag)) throw new IOException("该线路的 spider JAR 不可用");
        return isX86() ? adaptForX86(object) : json;
    }

    private static String cachedConfig(String json) {
        if (json == null || json.isEmpty()) return "";
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            if (PortalCatalog.array(object, "sites").isEmpty() && PortalCatalog.array(object, "urls").isEmpty()) return "";
            if (isX86() && PortalCatalog.array(object, "urls").isEmpty() && !hasSystemApiSite(object)) return "";
            return isX86() ? adaptForX86(object) : json;
        } catch (Exception e) {
            return "";
        }
    }

    /** Reject a TVBox config whose root spider is an HTML/404 response. */
    private static boolean usableConfig(String source, JsonObject object, String tag) {
        if (isX86()) return !PortalCatalog.array(object, "urls").isEmpty() || hasSystemApiSite(object);
        String spider = PortalCatalog.string(object, "spider");
        if (spider.isEmpty()) return true;
        String jar = spider.split(";md5;", 2)[0].trim();
        if (jar.isEmpty() || jar.startsWith("assets") || jar.startsWith("file")) return true;
        if (!jar.startsWith("http://") && !jar.startsWith("https://")) {
            try {
                jar = PortalCatalog.resolve(source, jar);
            } catch (RuntimeException e) {
                return false;
            }
        }
        Request request = new Request.Builder().url(jar).tag(tag).header("User-Agent", "Mozilla/5.0 Z10BOX/1.0").build();
        Call call = OkHttp.client().newCall(request);
        call.timeout().timeout(10000, TimeUnit.MILLISECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) return false;
            try (BufferedInputStream input = new BufferedInputStream(response.body().byteStream())) {
                byte[] magic = new byte[4];
                int count = 0;
                while (count < magic.length) {
                    int read = input.read(magic, count, magic.length - count);
                    if (read < 0) break;
                    count += read;
                }
                return count >= 2 && magic[0] == 'P' && magic[1] == 'K';
            }
        } catch (Exception e) {
            Log.w(TAG, "Spider JAR probe failed: " + jar, e);
            return false;
        }
    }

    private static boolean hasSystemApiSite(JsonObject object) {
        for (var element : PortalCatalog.array(object, "sites")) {
            if (!element.isJsonObject()) continue;
            if (siteType(element.getAsJsonObject()) != 3) return true;
        }
        return false;
    }

    private static String adaptForX86(JsonObject object) {
        JsonObject adapted = object.deepCopy();
        adapted.remove("spider");
        var sites = new com.google.gson.JsonArray();
        for (var element : PortalCatalog.array(object, "sites")) {
            if (!element.isJsonObject()) continue;
            if (siteType(element.getAsJsonObject()) != 3) sites.add(element);
        }
        adapted.add("sites", sites);
        return adapted.toString();
    }

    private static int siteType(JsonObject site) {
        try {
            return site.has("type") && !site.get("type").isJsonNull() ? site.get("type").getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static JsonObject live(String tag) throws Exception {
        List<PortalCatalog.Entry> entries = catalog(1, tag, false);
        register(entries, 1);
        JsonArray lives = new JsonArray();
        for (PortalCatalog.Entry entry : entries) {
            JsonObject item = new JsonObject();
            item.addProperty("name", entry.name());
            item.addProperty("type", 0);
            item.addProperty("url", entry.url());
            if (!entry.ua().isEmpty()) item.addProperty("ua", entry.ua());
            lives.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("lives", lives);
        return result;
    }

    public static int[] refresh() throws Exception {
        synchronized (PortalSources.class) {
            site = null;
        }
        List<PortalCatalog.Entry> vod = catalog(0, TAG, true);
        List<PortalCatalog.Entry> live = catalog(1, TAG, true);
        register(vod, 0);
        register(live, 1);
        return new int[]{vod.size(), live.size()};
    }

    private static void register(List<PortalCatalog.Entry> entries, int type) {
        // Inserting a new source never updates its selection timestamp.
        for (PortalCatalog.Entry entry : entries) Config.find(entry.url(), entry.name(), type).save();
    }

    @SuppressWarnings("unchecked")
    private static List<PortalCatalog.Entry> catalog(int type, String tag, boolean force) throws Exception {
        synchronized (LOCKS[type]) {
            if (!force && CATALOGS[type] != null && System.currentTimeMillis() - LOADED[type] < CATALOG_TTL) return (List<PortalCatalog.Entry>) CATALOGS[type];
            List<PortalCatalog.Entry> entries;
            try {
                JsonObject config = site(tag);
                JsonObject files = config.has("sourceFiles") && config.get("sourceFiles").isJsonObject() ? config.getAsJsonObject("sourceFiles") : new JsonObject();
                String path = PortalCatalog.string(files, type == 0 ? "list" : "live");
                if (path.isEmpty()) path = type == 0 ? "list.txt" : "livelist.txt";
                String text = fetch(PortalCatalog.resolve(HOME, path), tag);
                JsonArray custom = new JsonArray();
                if (type == 0) {
                    if (config.has("customApis") && config.get("customApis").isJsonArray()) custom = config.getAsJsonArray("customApis");
                    else if (!PortalCatalog.string(config, "customApis").isEmpty()) {
                        try {
                            custom = JsonParser.parseString(fetch(PortalCatalog.resolve(HOME, PortalCatalog.string(config, "customApis")), tag)).getAsJsonArray();
                        } catch (Exception e) {
                            if (Thread.currentThread().isInterrupted()) throw e;
                            Log.w(TAG, "Optional custom source list unavailable", e);
                        }
                    }
                }
                entries = PortalCatalog.parse(config, text, custom, type == 1, HOME);
                try {
                    save(type, PortalCatalog.encode(entries).toString());
                } catch (IOException e) {
                    Log.w(TAG, "Unable to save source catalog", e);
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) throw e;
                Log.w(TAG, "Using local source catalog", e);
                entries = fallback(type);
                if (force) throw new IOException("网站暂不可用，已保留上次源清单", e);
            }
            CATALOGS[type] = entries;
            LOADED[type] = System.currentTimeMillis();
            return entries;
        }
    }

    private static synchronized JsonObject site(String tag) throws Exception {
        if (site != null && System.currentTimeMillis() - siteLoaded < CATALOG_TTL) return site;
        JsonObject object = JsonParser.parseString(fetch(HOME + "config.json", tag)).getAsJsonObject();
        if (!object.has("sourceFiles")) throw new IOException("网站配置格式无效");
        siteLoaded = System.currentTimeMillis();
        return site = object;
    }

    private static String fetch(String url, String tag) throws IOException {
        Request request = new Request.Builder().url(url).tag(tag).header("User-Agent", "Mozilla/5.0 Z10BOX/1.0").header("Referer", HOME).header("Cache-Control", "no-cache").build();
        Call call = OkHttp.client().newCall(request);
        call.timeout().timeout(12, TimeUnit.SECONDS);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) throw new IOException("HTTP " + response.code());
            String text = response.body().string();
            if (text.trim().startsWith("<")) throw new IOException("网站返回了 HTML 页面");
            return text;
        }
    }

    private static List<PortalCatalog.Entry> fallback(int type) throws IOException {
        File file = cache(type);
        if (file.isFile()) {
            try {
                return PortalCatalog.decode(new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8));
            } catch (RuntimeException | IOException e) {
                Log.w(TAG, "Invalid cached catalog", e);
            }
        }
        try (InputStream stream = App.get().getAssets().open("z10/" + (type == 0 ? "vod" : "live") + ".json")) {
            return PortalCatalog.decode(Path.read(stream));
        }
    }

    private static File cache(int type) {
        return Path.files("z10-sources-" + type + ".json");
    }

    @SuppressWarnings("unchecked")
    private static boolean knownSource(String url) throws IOException {
        synchronized (LOCKS[0]) {
            List<PortalCatalog.Entry> entries = CATALOGS[0] == null ? fallback(0) : (List<PortalCatalog.Entry>) CATALOGS[0];
            return entries.stream().anyMatch(entry -> entry.urls().contains(url));
        }
    }

    private static void save(int type, String text) throws IOException {
        AtomicFile file = new AtomicFile(cache(type));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(text.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException e) {
            file.failWrite(stream);
            throw e;
        }
    }
}
