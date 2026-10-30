package top.hibermod.ctdapi.diagnostics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 统一的「不该静默」失败出口。
 *
 * <h2>它解决什么</h2>
 * 一个模组里最难查的 bug 不是崩溃，而是**功能悄悄失效**：
 * {@code catch (Throwable ignored) {}} 把异常吃掉，系统继续跑，
 * 从外面看只是「某个效果没出现」。
 *
 * <p>本项目吃过两次这种亏：`/ctd reload` 一直失败并自动回滚（只有一行 WARN，
 * 藏了好几轮）；宿主侧十余处 `catch (Throwable ignored)` 让速度施加、
 * 拓扑重建、渲染的失败全无声息。
 *
 * <h2>语义</h2>
 * <ul>
 *   <li>某点**第一次**失败 → ERROR，带完整异常；</li>
 *   <li>之后每 100 次 → WARN，附累计次数；</li>
 *   <li>计数一直保留，任何诊断界面都能读到「哪里在悄悄失败」。</li>
 * </ul>
 *
 * <p>放在 API 层是因为**宿主与脚本运行时都要用**：
 * 两个模组各记各的，排查时要翻两个地方，等于没有统一视图。
 */
public final class Failures {

    private static final Logger LOG = LoggerFactory.getLogger("ctd");

    private static final Map<String, AtomicLong> COUNTS = new ConcurrentHashMap<>();

    private Failures() {}

    /**
     * 记录一次被兜住的失败。
     *
     * @param where 位置标识（例如 {@code "Train#tick 注入"}），同时作为计数键
     * @param t     原始异常；允许 {@code null}（有些地方只有失败没有异常）
     */
    public static void caught(String where, Throwable t) {
        if (where == null) where = "(unlabeled)";

        AtomicLong counter = COUNTS.computeIfAbsent(where, k -> new AtomicLong());
        long n = counter.incrementAndGet();

        if (n == 1) {
            LOG.error("[ctd] {} failed (first time, will keep counting): {}", where, describe(t), t);
        } else if (n % 100 == 0) {
            LOG.warn("[ctd] {} has failed {} times; still continuing", where, n);
        }
    }

    public static long count(String where) {
        AtomicLong c = COUNTS.get(where);
        return c == null ? 0L : c.get();
    }

    /** 所有出过问题的点与次数，按出现顺序。 */
    public static Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        COUNTS.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    public static boolean any() {
        return !COUNTS.isEmpty();
    }

    public static void resetForTests() {
        COUNTS.clear();
    }

    private static String describe(Throwable t) {
        if (t == null) return "(no exception)";
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
