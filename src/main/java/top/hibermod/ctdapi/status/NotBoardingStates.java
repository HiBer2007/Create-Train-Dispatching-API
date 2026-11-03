package top.hibermod.ctdapi.status;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「请勿上车」状态：由**调度系统**给出，按「车站 + 站台」记录。
 *
 * <p>适用场景：通过不停站的车、即将退出运营的车、回库车——此时该站台不应当载客。
 * 和扣车一样，这不是能从车次数据推出来的量，必须由调度显式下发。
 */
public final class NotBoardingStates {

    private NotBoardingStates() {}

    private static final Map<String, Boolean> BLOCKED = new ConcurrentHashMap<>();

    public static String key(String station, String platform) {
        return (station == null ? "" : station) + "|" + (platform == null ? "" : platform);
    }

    public static void put(String station, String platform, boolean blocked) {
        String k = key(station, platform);
        if (blocked) {
            BLOCKED.put(k, Boolean.TRUE);
        } else {
            BLOCKED.remove(k);
        }
    }

    public static boolean isBlocked(String station, String platform) {
        return BLOCKED.getOrDefault(key(station, platform), Boolean.FALSE);
    }

    public static int size() {
        return BLOCKED.size();
    }

    public static void clear() {
        BLOCKED.clear();
    }
}