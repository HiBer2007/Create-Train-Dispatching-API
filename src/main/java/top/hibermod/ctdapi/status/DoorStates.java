package top.hibermod.ctdapi.status;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 屏蔽门开关状态：由**调度系统**给出，按「车站 + 站台」记录。
 *
 * <p>屏蔽门挡板（CTD PSD）用它点亮「开启 / 关闭」指示灯，同时与门号联动。
 */
public final class DoorStates {

    private DoorStates() {}

    private static final Map<String, Boolean> OPEN = new ConcurrentHashMap<>();

    public static String key(String station, String platform) {
        return (station == null ? "" : station) + "|" + (platform == null ? "" : platform);
    }

    /** @param open true 表示门已开启（放客），false 表示关闭。 */
    public static void put(String station, String platform, boolean open) {
        String k = key(station, platform);
        if (open) {
            OPEN.put(k, Boolean.TRUE);
        } else {
            OPEN.remove(k);
        }
    }

    public static boolean isOpen(String station, String platform) {
        return OPEN.getOrDefault(key(station, platform), Boolean.FALSE);
    }

    public static int size() {
        return OPEN.size();
    }

    public static void clear() {
        OPEN.clear();
    }
}
