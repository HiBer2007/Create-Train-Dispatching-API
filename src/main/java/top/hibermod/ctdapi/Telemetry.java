package top.hibermod.ctdapi;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 每帧的**列车实时状态**。只读，由宿主从 Create 的 {@code Train} 采样后构造。
 *
 * <p>所有距离单位为「格」，速度单位为「格/tick」（与 Create 内部一致，不是 m/s）。
 * 换算：{@code 格/tick * 20 = 格/秒}。
 */
public interface Telemetry {

    Collection<TrainState> trains();

    /** 不存在时返回 {@code null}。 */
    TrainState train(UUID id);

    /**
     * 一列车的完整实时状态。
     *
     * @param id               列车 UUID（{@code Train.id}，存档稳定）
     * @param name             显示名
     * @param length           全长（格），= 车尾到车头
     * @param leadingEdge      车头所在边
     * @param leadingDistance  车头沿 {@code leadingEdge} 规范化方向的距离
     * @param trailingEdge     车尾所在边
     * @param trailingDistance 车尾沿 {@code trailingEdge} 规范化方向的距离
     * @param footprint        车尾→车头的有序连续区间（可能跨多条边）。相邻区段首尾相接。
     * @param speed            当前速度，带符号：正 = 沿当前进路前进
     * @param targetSpeed      Create 本帧算出的目标速度（宿主的「原生意图」，可参考）
     * @param acceleration     当前可用加速度（格/tick²）。制动与加速同值——Create 没有独立制动力。
     * @param maxSpeed         本车当前最大速度（含燃料/油门修正）
     * @param carriages        车厢数
     * @param doubleEnded      是否双端驾驶台
     * @param manuallyDriven   本帧是否人工驾驶（人工时自动系统应让行）
     * @param derailed         是否脱轨
     * @param odometer         自上车以来的累计里程（格），宿主维护，单调递增
     * @param route            当前己方进路状态，无进路时为 {@code null}
     * @param authority        当前生效的距离授权（格）。{@code <= 0} 表示必须停车。
     */
    record TrainState(
        UUID id,
        Component name,
        double length,
        Rail.EdgeId leadingEdge,
        double leadingDistance,
        Rail.EdgeId trailingEdge,
        double trailingDistance,
        List<Rail.Span> footprint,
        double speed,
        double targetSpeed,
        double acceleration,
        double maxSpeed,
        int carriages,
        boolean doubleEnded,
        boolean manuallyDriven,
        boolean derailed,
        double odometer,
        RouteState route,
        double authority,
        top.hibermod.ctdapi.traction.Traction.PowerState power,
        java.util.Set<ResourceLocation> signalSystems
    ) {
        /**
         * 以 {@code v} 的速度在距离 {@code d} 内刹停所需时间的近似（格/tick²）。
         * 用于内核判断「还来不来得及减速」。
         */
        public static double stoppingDistance(double v, double acceleration) {
            if (acceleration <= 0) return Double.POSITIVE_INFINITY;
            return (v * v) / (2 * acceleration);
        }

        /** 该车当前是否已经不可能在 {@code distance} 内停住。 */
        public boolean overshoots(double distance) {
            return stoppingDistance(Math.abs(speed), acceleration) > distance;
        }
    }

    /**
     * 己方进路的状态。由宿主维护（内核通过 {@link Command.RouteRequest} 申请）。
     *
     * @param routeId        进路标识
     * @param remaining      进路剩余长度（格），从车头算起
     * @param lockedSections 本进路已锁闭的区段
     * @param lockedSwitches 本进路已锁定的岔口（节点）
     * @param committed      是否已「锁死」：一旦列车进入进路首区段，便不可再撤销
     */
    record RouteState(
        RouteId routeId,
        double remaining,
        List<Topology.SectionId> lockedSections,
        List<Rail.NodeId> lockedSwitches,
        boolean committed
    ) {}

    /** 进路标识，由宿主按申请顺序分配。 */
    record RouteId(long value) {}
}
