package top.hibermod.ctdapi.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内核最后一次下发的移动授权，按列车存放。
 *
 * <h2>为什么放在契约层</h2>
 * 写它的是内核（脚本运行时），读它的是宿主（要把授权推给客户端）、
 * 显示器和诊断命令。三方分属不同仓库，所以这份「内核说过什么」的单一事实
 * 必须放在大家都能看见的地方。
 *
 * <h2>为什么用 0 / -1 表示「没有」</h2>
 * {@code 0} 距离授权是有意义的（车就在许可终点前），所以「没有授权」不能用 0 表示，
 * 这里用「没有条目」区分；允许速度用 {@code -1} 表示「内核没说过」。
 * 显示端拿到 -1 时应当显示未知，而不是显示 0 km/h——那会被误读成「信号要求停车」。
 */
public final class Authority {

    private static final Map<UUID, Double> DISTANCE = new ConcurrentHashMap<>();
    private static final Map<UUID, Double> SPEED_CAP = new ConcurrentHashMap<>();

    private Authority() {}

    /** 内核下发：授权距离 + 允许速度（blocks/tick）。 */
    public static void put(UUID trainId, double distance, double speedCap) {
        if (trainId == null) return;
        DISTANCE.put(trainId, distance);
        if (speedCap >= 0) {
            SPEED_CAP.put(trainId, speedCap);
        } else {
            SPEED_CAP.remove(trainId);
        }
    }

    /** 只给距离时的便捷写法。 */
    public static void put(UUID trainId, double distance) {
        put(trainId, distance, -1);
    }

    /** 最近一次授权距离；没有记录返回 0。用 {@link #has(UUID)} 区分「没有」。 */
    public static double distance(UUID trainId) {
        Double v = trainId == null ? null : DISTANCE.get(trainId);
        return v == null ? 0.0 : v;
    }

    /** 最近一次允许速度（blocks/tick）；没有记录返回 -1。 */
    public static double speedCap(UUID trainId) {
        Double v = trainId == null ? null : SPEED_CAP.get(trainId);
        return v == null ? -1.0 : v;
    }

    /** 内核对这条列车说过话吗。 */
    public static boolean has(UUID trainId) {
        return trainId != null && DISTANCE.containsKey(trainId);
    }

    public static void clear(UUID trainId) {
        DISTANCE.remove(trainId);
        SPEED_CAP.remove(trainId);
    }

    public static void clearAll() {
        DISTANCE.clear();
        SPEED_CAP.clear();
    }

    public static int size() {
        return DISTANCE.size();
    }
}
