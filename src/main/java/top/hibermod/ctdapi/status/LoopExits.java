package top.hibermod.ctdapi.status;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「环线列车即将退出运营」标记：由**调度系统**给出，按「车站 + 站台」记录。
 *
 * <p>语义：该站台当前这一趟环线车已经跑完最后一个完整环，下一圈就会退出运营，
 * 此时显示件应当把「内环/外环」换成它的终点站（回库/清客方向）。
 * 正常环线运营期间保持 false，显示环别。
 */
public final class LoopExits {

    private LoopExits() {}

    private static final Map<String, Boolean> EXITING = new ConcurrentHashMap<>();

    public static String key(String station, String platform) {
        return (station == null ? "" : station) + "|" + (platform == null ? "" : platform);
    }

    public static void put(String station, String platform, boolean exiting) {
        String k = key(station, platform);
        if (exiting) {
            EXITING.put(k, Boolean.TRUE);
        } else {
            EXITING.remove(k);
        }
    }

    public static boolean isExiting(String station, String platform) {
        return EXITING.getOrDefault(key(station, platform), Boolean.FALSE);
    }

    public static int size() {
        return EXITING.size();
    }

    public static void clear() {
        EXITING.clear();
    }
}