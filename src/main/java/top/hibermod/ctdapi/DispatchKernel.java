package top.hibermod.ctdapi;

import net.minecraft.resources.ResourceLocation;

/**
 * 调度内核：整套信号与自动驾驶的**大脑**。
 *
 * <p>宿主（host 模组）只负责三件事：把 Create 的状态翻译成 {@link FrameContext}、
 * 把 {@link DispatchFrame} 翻译成对 Create 的写入、以及在安全边界上兜底。
 * 「怎么调度」全部在内核里。
 *
 * <h2>生命周期</h2>
 * <pre>
 *   register()  →  onAttach(env)  →  [ 每 tick: onFrame(in, out) ]
 *                                      ├─ onTopologyChanged(delta, env)   （拓扑变化时）
 *                                      ├─ onEvent(event, env)             （事件到达时，先于该帧 onFrame）
 *                                      └─ ...
 *                               →  onDetach()
 * </pre>
 *
 * <h2>线程与预算</h2>
 * 全部回调在服务端主线程。{@link KernelEnv.Settings#tickBudgetMillis()} 是软预算，
 * 超时不会中止，但会写 WARN。超大线网请把重计算放进拓扑事件里做增量，不要每帧全量重算。
 *
 * <h2>错误处理</h2>
 * {@link #onFrame} 抛出的异常会被宿主捕获：
 * 若 {@link KernelEnv.Settings#failSafeOnError()} 为 true，则该帧的指令**全部丢弃**，
 * 列车退回 Create 原版行为，并发出 {@link RailwayEvent.KernelFault}。
 * 内核必须假定自己随时可能被降级。
 */
public interface DispatchKernel {

    /** 内核唯一标识，必须在自己的命名空间下。 */
    ResourceLocation id();

    /** 兼容的 API 版本。不匹配时宿主拒绝加载。 */
    default int apiVersion() {
        return 1;
    }

    /** 内核被启用。此时 {@link KernelEnv#state()} 已恢复完毕。 */
    void onAttach(KernelEnv env);

    /**
     * 每个调度帧调用一次。
     *
     * <p>这是唯一的决策入口。内核应在此读取 {@code in}、写入 {@code out}。
     * 不写任何指令等价于「不干预」。
     */
    void onFrame(FrameContext in, DispatchFrame out);

    /**
     * 拓扑变化。{@code delta.structureBroke()} 为 true 时建议全量重建缓存。
     *
     * <p>在 {@link #onFrame} 之前调用，顺序确定。
     */
    default void onTopologyChanged(Topology.TopologyDelta delta, KernelEnv env) {}

    /** 事件到达。在当帧 {@link #onFrame} 之前调用。 */
    default void onEvent(RailwayEvent event, KernelEnv env) {}

    /** 内核被停用/替换。请在这里释放资源，不要依赖它保存状态（用 {@link KernelEnv#markStateDirty()}）。 */
    default void onDetach() {}
}
