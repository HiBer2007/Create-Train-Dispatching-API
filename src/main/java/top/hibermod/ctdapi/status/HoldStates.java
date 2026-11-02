package top.hibermod.ctdapi.status;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扣车状态：由**调度内核**给出，按「车站 + 站台」记录。
 *
 * <p>为什么要单独一张表：扣车不是一个可以「从车次数据推出来」的量——
 * 它是调度员的决定。所以内核写、显示件读，中间不经过任何推断。
 *
 * <p>键用 {@code 车站|站台}，两者都可以是任意字符串（与 CRN 的站名一致即可）。
 */
public final class HoldStates {

    private HoldStates() {}

    private static final Map<String, Boolean> HELD = new ConcurrentHashMap<>();

    /** 键：车站 + 站台。 */
    public static String key(String station, String platform) {
        return (station == null ? "" : station) + "|" + (platform == null ? "" : platform);
    }

    public static void put(String station, String platform, boolean held) {
        String k = key(station, platform);
        if (held) {
            HELD.put(k, Boolean.TRUE);
        } else {
            HELD.remove(k);
        }
    }

    public static boolean isHeld(String station, String platform) {
        return HELD.getOrDefault(key(station, platform), Boolean.FALSE);
    }

    public static int size() {
        return HELD.size();
    }

    public static void clear() {
        HELD.clear();
    }
}