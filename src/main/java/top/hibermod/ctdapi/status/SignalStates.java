package top.hibermod.ctdapi.status;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每架信号机**当前显示哪一个语义**（由调度内核决定）。
 *
 * <h2>为什么不复用 Create 的 SignalState</h2>
 * Create 的状态只有「红 / 非红」两档，因为它的信号机只有一个灯。
 * 我们要表达的是双灯组合（绿-绿、绿-黄、绿-红…），那套语义塞不进它的枚举，
 * 也不该塞——它的是「闭塞占用状态」，我们的是「移动授权的梯度」。
 *
 * <p>这里存的是内核写下的语义 id（如 {@code ctd:approach}），
 * 服务端写入后同步给客户端，客户端渲染时按 id 决定灯色与组合。
 */
public final class SignalStates {

    private static final Map<UUID, String> ASPECT = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> STAMP = new ConcurrentHashMap<>();

    private SignalStates() {}

    /** 内核写入；{@code aspectId} 为空表示灭灯。 */
    public static void put(UUID signalId, String aspectId) {
        if (signalId == null) return;
        if (aspectId == null || aspectId.isBlank()) {
            ASPECT.remove(signalId);
            STAMP.remove(signalId);
            return;
        }
        ASPECT.put(signalId, aspectId);
        STAMP.put(signalId, System.currentTimeMillis());
    }

    /** 当前语义 id；没有返回空串。 */
    public static String get(UUID signalId) {
        String v = signalId == null ? null : ASPECT.get(signalId);
        return v == null ? "" : v;
    }

    /** 最近一次更新时间（毫秒），用于判断显示是不是过期了。 */
    public static long stamp(UUID signalId) {
        Long v = signalId == null ? null : STAMP.get(signalId);
        return v == null ? 0L : v;
    }

    public static Map<UUID, String> snapshot() {
        return Map.copyOf(ASPECT);
    }

    public static void clear() {
        ASPECT.clear();
        STAMP.clear();
    }

    public static int size() {
        return ASPECT.size();
    }
}
