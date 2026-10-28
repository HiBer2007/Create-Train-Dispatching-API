package top.hibermod.ctdapi;

import net.minecraft.nbt.CompoundTag;

/**
 * 内核与宿主之间的**生命周期服务**。
 *
 * <p>线程模型：全部回调都在**服务端主线程**执行。内核不要自己起线程去改状态；
 * 若要读外部数据（如网页请求），请把结果缓存下来，在主线程消费。
 */
public interface KernelEnv {

    /** 服务端游戏刻。 */
    long gameTime();

    /**
     * 内核私有的持久化数据。宿主负责在存档时序列化、加载时恢复。
     *
     * <p>约定：
     * <ul>
     *   <li>键名以 {@code ""} 顶层自由使用，宿主不会读写任何键。</li>
     *   <li>切换内核时旧数据保留不删，便于回滚。</li>
     *   <li>首次加载返回空 {@code CompoundTag}，**永不为 null**。</li>
     * </ul>
     * 修改后必须调用 {@link #markStateDirty()}。
     */
    CompoundTag state();

    /** 标记内核状态需要落盘。 */
    void markStateDirty();

    /** 当前拓扑快照。等价于帧内的 {@link FrameContext#topology()}。 */
    Topology topology();

    /**
     * 写日志。宿主会加上内核 id 前缀并限速（同一消息每 tick 最多一条）。
     *
     * @param level {@code "info"} / {@code "warn"} / {@code "error"}
     */
    void log(String level, String message, Object... args);

    /** 配置。由玩家在 host 模组的配置文件里设置。 */
    Settings settings();

    /**
     * 宿主配置。刻意做成扁平结构——内核不应该依赖某个具体配置框架。
     *
     * @param maxTrainLength      线路允许的最长列车（格）
     * @param failSafeOnError     内核抛异常时是否退回原版行为（强烈建议 true）
     * @param tickBudgetMillis    单帧调度时间预算，超出将写 WARN
     * @param extra               宿主透传的自由配置（键值对字符串）
     */
    record Settings(
        double maxTrainLength,
        boolean failSafeOnError,
        double tickBudgetMillis,
        java.util.Map<String, String> extra
    ) {
        public String get(String key, String fallback) {
            return extra.getOrDefault(key, fallback);
        }

        public double getDouble(String key, double fallback) {
            String v = extra.get(key);
            if (v == null) return fallback;
            try { return Double.parseDouble(v); } catch (NumberFormatException e) { return fallback; }
        }

        public int getInt(String key, int fallback) {
            String v = extra.get(key);
            if (v == null) return fallback;
            try { return Integer.parseInt(v); } catch (NumberFormatException e) { return fallback; }
        }
    }
}
