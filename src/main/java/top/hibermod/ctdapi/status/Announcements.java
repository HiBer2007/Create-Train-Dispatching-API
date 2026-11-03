package top.hibermod.ctdapi.status;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车站公告：由**调度系统**发布，按「车站 + 站台」记录。
 *
 * <p>显示件把它放在右栏的「通知与提示区」。没有公告时该区域**留空**，不显示占位文字。
 */
public final class Announcements {

    private Announcements() {}

    private static final Map<String, String> TEXT = new ConcurrentHashMap<>();

    public static String key(String station, String platform) {
        return (station == null ? "" : station) + "|" + (platform == null ? "" : platform);
    }

    public static void put(String station, String platform, String text) {
        String k = key(station, platform);
        if (text == null || text.isBlank()) {
            TEXT.remove(k);
        } else {
            TEXT.put(k, text);
        }
    }

    public static String get(String station, String platform) {
        return TEXT.getOrDefault(key(station, platform), "");
    }

    public static int size() {
        return TEXT.size();
    }

    public static void clear() {
        TEXT.clear();
    }
}