package top.hibermod.ctdapi;

import java.util.UUID;

import net.minecraft.resources.ResourceLocation;

/**
 * 铁路事件。内核可用它做事件驱动调度，宿主也用它喂 PIS。
 *
 * <p>事件是**不可变事实**，不承诺顺序之外的一致性；同一帧内可能批量出现。
 */
public sealed interface RailwayEvent {

    /** 事件发生的游戏刻。 */
    long gameTime();

    // ── 列车 ──────────────────────────────────────────────────────────────

    /**
     * 列车到达车站。
     *
     * @param stationId 车站 UUID（{@code GlobalStation.id}）
     * @param odometer  到站时的累计里程，用于计算区间运行时分
     */
    record TrainArrived(long gameTime, UUID trainId, UUID stationId, double odometer) implements RailwayEvent {}

    /** 列车离开车站。 */
    record TrainDeparted(long gameTime, UUID trainId, UUID stationId) implements RailwayEvent {}

    /** 列车被扣停。{@code reason} 会进 PIS 播报。 */
    record TrainHeld(long gameTime, UUID trainId, String reason) implements RailwayEvent {}

    /** 列车脱轨或出现结构性故障：宿主已停止对该车的自动控制。 */
    record TrainFaulted(long gameTime, UUID trainId, String detail) implements RailwayEvent {}

    // ── 进路 / 岔口 ───────────────────────────────────────────────────────

    /** 进路已排列并锁闭。 */
    record RouteSet(long gameTime, Telemetry.RouteId routeId, UUID trainId, int sections, int switches)
        implements RailwayEvent {}

    /**
     * 进路被撤销/解锁。
     *
     * @param reason  撤销原因
     * @param partial 是否列车已部分进入（此时属于「进路不可撤销」的边界情况）
     */
    record RouteCancelled(long gameTime, Telemetry.RouteId routeId, String reason, boolean partial)
        implements RailwayEvent {}

    /** 岔口锁闭状态变化。 */
    record SwitchLocked(long gameTime, Rail.NodeId node, Rail.EdgeId branch, long holder) implements RailwayEvent {}

    // ── 信号 ──────────────────────────────────────────────────────────────

    /** 某面信号显示变化。{@code from} / {@code to} 是 Aspect id。 */
    record SignalChanged(
        long gameTime,
        UUID signalId,
        Topology.SignalSide side,
        ResourceLocation from,
        ResourceLocation to
    ) implements RailwayEvent {}

    // ── 安全 ──────────────────────────────────────────────────────────────

    /**
     * 检测到两车占用冲突（正常情况下不应发生）。
     *
     * @param a 列车 A
     * @param b 列车 B
     * @param overlap 重叠的区间
     */
    record OccupancyConflict(long gameTime, UUID a, UUID b, Rail.Span overlap) implements RailwayEvent {}

    /** 内核自身抛出异常被宿主捕获。宿主会退化为原版行为（fail-safe）。 */
    record KernelFault(long gameTime, String kernelId, String message) implements RailwayEvent {}

    // ── 拓扑 ──────────────────────────────────────────────────────────────

    record TopologyChanged(long gameTime, Topology.TopologyDelta delta) implements RailwayEvent {}
}
