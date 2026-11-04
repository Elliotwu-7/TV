package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Session-only guard for sources that contain adult content. */
public final class SensitiveContent {

    private static final String PASSWORD = "miao";
    private static final List<String> SITE_KEYS = List.of("采集集合", "js集合", "php集合", "python集合");
    private static final List<String> SITE_APIS = List.of("/cj/cjjh.php", "cjjh.php");
    private static final List<String> CONTENT_KEYWORDS = List.of(
            "瑟瑟", "涩涩", "色色", "黄片", "A片", "AV片", "无码", "有码", "麻豆传媒",
            "制服诱惑", "三级伦理", "三级片", "AI换脸", "色情", "情色", "淫秽", "成人视频",
            "成人影片", "成人片", "成人动画", "成人动漫", "成人漫画", "成人小说", "成人影院",
            "成人直播", "成人福利", "成人激情", "里番", "18禁", "禁片", "福利姬", "福利视频",
            "福利片", "写真视频", "激情视频", "午夜视频", "两性视频", "伦理视频", "伦理片",
            "无删减", "未删减", "偷拍视频", "自慰视频", "探花视频", "约炮视频", "人妻视频",
            "少妇视频", "裸聊", "裸舞", "黄播", "擦边视频");

    private static volatile boolean unlocked;

    private SensitiveContent() {
    }

    public static boolean isUnlocked() {
        return unlocked;
    }

    public static boolean unlock(String password) {
        if (!isPassword(password)) return false;
        unlocked = true;
        return true;
    }

    public static boolean isPassword(String password) {
        return PASSWORD.equals(password == null ? "" : password.trim());
    }

    public static boolean isSensitiveSite(Site site) {
        return site != null && isSensitiveSite(site.getKey(), site.getName(), site.getApi());
    }

    public static boolean isSiteVisible(Site site) {
        return unlocked || !isSensitiveSite(site);
    }

    public static boolean isSensitiveSite(String key, String name, String api) {
        return contains(key, SITE_KEYS) || contains(name, SITE_KEYS) || contains(api, SITE_APIS);
    }

    public static boolean isSensitiveType(Class type) {
        return type != null && contains(type.getTypeName(), CONTENT_KEYWORDS);
    }

    public static boolean isTypeVisible(Class type) {
        return unlocked || !isSensitiveType(type);
    }

    public static List<Class> filterTypes(List<Class> items) {
        List<Class> filtered = new ArrayList<>();
        if (items != null) for (Class item : items) if (isTypeVisible(item)) filtered.add(item);
        return filtered;
    }

    public static boolean isSensitiveVod(Vod vod) {
        if (vod == null) return false;
        if (vod.getSite() != null && isSensitiveSite(vod.getSite())) return true;
        return contains(vod.getName(), CONTENT_KEYWORDS)
                || contains(vod.getTypeName(), CONTENT_KEYWORDS)
                || contains(vod.getRemarks(), CONTENT_KEYWORDS)
                || contains(vod.getContent(), CONTENT_KEYWORDS);
    }

    public static boolean isVodVisible(Vod vod) {
        return unlocked || !isSensitiveVod(vod);
    }

    public static List<Vod> filterVods(List<Vod> items) {
        List<Vod> filtered = new ArrayList<>();
        if (items != null) for (Vod item : items) if (isVodVisible(item)) filtered.add(item);
        return filtered;
    }

    public static boolean isRecordVisible(String siteKey, String siteName) {
        return isRecordVisible(siteKey, siteName, "");
    }

    public static boolean isRecordVisible(String siteKey, String siteName, String vodName) {
        return unlocked || (!isSensitiveSite(siteKey, siteName, "") && !contains(vodName, CONTENT_KEYWORDS));
    }

    public static boolean isLaunchVisible(String siteKey, String vodName) {
        return isRecordVisible(siteKey, "", vodName);
    }

    public static Result filterResult(Result result) {
        if (result == null) return Result.empty();
        result.setTypes(filterTypes(result.getTypes()));
        result.setList(filterVods(result.getList()));
        return result;
    }

    private static boolean contains(String value, List<String> needles) {
        if (value == null || value.isEmpty()) return false;
        String text = value.toLowerCase(Locale.ROOT);
        for (String needle : needles) if (text.contains(needle.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
}
