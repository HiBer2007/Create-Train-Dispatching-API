package top.hibermod.ctdapi;

import java.util.Collection;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;

/**
 * 单次调度帧的**输入**：拓扑 + 列车 + 上一帧的设施状态。
 *
 * <p>调用时机（宿主保证）：
 * <pre>
 *   ① Create.RAILWAYS.tick() 之前            ← 感知上一帧结果
 *   ② 全部列车 tick 之后                     ← 采集本帧遥测
 *   ③ 设施状态应用之前                       ← {@link DispatchFrame} 被消费
 * </pre>
 * 因此内核看到的是「本帧列车已经走完」的状态，写下的指令作用于**下一帧**的物理推进。
 */
public interface FrameContext {

    /** 服务端游戏刻。 */
    long gameTime();

    /** 拓扑快照，本帧内不变。 */
    Topology topology();

    /** 全部列车的实时状态。 */
    Telemetry telemetry();

    /** 所有信号机的**当前显示**（上一帧应用后的结果）。 */
    Collection<SignalState> signalStates();

    /** 所有岔口的**当前锁闭**状态。 */
    Collection<SwitchState> switchStates();

    /** 本帧已处理但尚未被内核消费的铁路事件（到站、发车、异常）。 */
    Collection<RailwayEvent> pendingEvents();

    /**
     * 一台信号机某一面的当前显示。
     *
     * @param aspectId    当前 Aspect 的 id（未注册/未显示时为 {@code null}）
     * @param requestedBy 当前持有该显示的进路 id，{@code 0} 表示原版/无主
     */
    record SignalState(UUID signalId, Topology.SignalSide side, ResourceLocation aspectId, long requestedBy) {}

    /**
     * 一个岔口的当前锁闭。
     *
     * @param lockedBranch 当前锁定的支线；{@code null} 表示未锁
     * @param holder       持锁的进路 id
     * @param contested    本帧有多条进路竞争该岔口（宿主已检测到冲突）
     */
    record SwitchState(Rail.NodeId node, Rail.EdgeId lockedBranch, long holder, boolean contested) {}
}
