package top.hibermod.ctdapi.route;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 车站ID + 站台ID → 车站方块位置。由脚本注册，挡板据此取本站数据。 */
public final class StationBindings {

    private StationBindings() {}

    public record Binding(String station, String platform, int x, int y, int z) {}

    private static final Map<String, Binding> BINDINGS = new ConcurrentHashMap<>();

    private static String key(String station, String platform) {
        return (station == null ? "" : station) + "|" + (platform == null ? "" : platform);
    }

    public static void put(String station, String platform, int x, int y, int z) {
        BINDINGS.put(key(station, platform), new Binding(station, platform, x, y, z));
    }

    public static Binding get(String station, String platform) {
        return BINDINGS.get(key(station, platform));
    }

    public static int size() {
        return BINDINGS.size();
    }

    public static void clear() {
        BINDINGS.clear();
    }
}
