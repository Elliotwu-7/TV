import com.fongmi.android.tv.api.config.PortalCatalog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Standalone contract checks: runs without an emulator or the optional media runtimes. */
public final class PortalCatalogChecks {
    private static final String HOME = "https://0.12yue.de5.net/";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length == 0 ? "." : args[0]);
        Path fixtures = root.resolve("verification/fixtures");
        JsonObject config = JsonParser.parseString(Files.readString(fixtures.resolve("portal-config.json"))).getAsJsonObject();
        List<PortalCatalog.Entry> vod = PortalCatalog.parse(config, Files.readString(fixtures.resolve("list.txt")), new JsonArray(), false, HOME);
        check(vod.size() == 55, "all 55 recorded website rows are available");
        check(vod.get(0).name().equals("饭太硬"), "website priority selects the expected default");
        check(vod.get(0).url().equals(HOME + "tvbox/%E9%A5%AD%E5%A4%AA%E7%A1%AC.json"), "Chinese source filenames form usable URLs on the requested site");
        check(vod.get(0).urls().size() == 6, "all five mirrors and the original endpoint are retained");
        check(vod.stream().anyMatch(entry -> entry.name().equals("高天流云js")), "lower-priority entries remain available");
        check(PortalCatalog.decode(PortalCatalog.encode(vod).toString()).equals(vod), "cached catalog preserves names and endpoints");

        List<PortalCatalog.Entry> live = PortalCatalog.parse(config, Files.readString(fixtures.resolve("livelist.txt")), new JsonArray(), true, HOME);
        check(live.size() == 2, "TXT and M3U live sources are both imported");
        check(live.get(0).url().equals("https://ztha.top/TVBox/lib/interface.txt"), "live source uses the original endpoint declared by the site");
        check(live.get(0).ua().isEmpty(), "literal null does not become a User-Agent");

        JsonArray custom = JsonParser.parseString("[{\"name\":\"额外源\",\"urls\":[{\"url\":\"https://example.org/vod\"},{\"url\":\"https://example.org/vod\"}]},{\"name\":\"无效\",\"urls\":[{\"url\":\"javascript:alert(1)\"}]}]").getAsJsonArray();
        List<PortalCatalog.Entry> mixed = PortalCatalog.parse(config, "\uFEFF饭太硬.json|20261030|16K|http://example.org/original\r\n饭太硬.json|20261030|16K|http://example.org/original\r\n", custom, false, HOME);
        check(mixed.size() == 2 && mixed.get(1).urls().size() == 1, "custom sources and duplicate rows do not create duplicate choices");
        rejected(() -> PortalCatalog.parse(config, "<!DOCTYPE html>\n<html>站点维护</html>", new JsonArray(), false, HOME), "HTML is rejected as a source catalog");
        rejected(() -> PortalCatalog.parse(config, "", new JsonArray(), false, HOME), "empty responses are rejected");
        rejected(() -> PortalCatalog.decode("[{\"name\":\"坏地址\",\"urls\":[\"file:///system/build.prop\"]}]"), "non-HTTP cache entries are rejected");
        rejected(() -> PortalCatalog.parse(config, "../outside.json", new JsonArray(), false, HOME), "invalid filenames cannot escape the catalog path");

        if (args.length > 1 && args[1].equals("--snapshot")) {
            Path assets = root.resolve("app/src/main/assets/z10");
            Files.createDirectories(assets);
            Files.writeString(assets.resolve("vod.json"), PortalCatalog.encode(vod).toString() + "\n");
            Files.writeString(assets.resolve("live.json"), PortalCatalog.encode(live).toString() + "\n");
        }
        System.out.println("PASS: website catalog, priorities, mirrors, live formats, cache, custom sources, invalid responses");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void rejected(Runnable action, String label) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(label);
    }
}
