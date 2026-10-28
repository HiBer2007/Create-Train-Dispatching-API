package top.hibermod.ctdapi;

import java.util.List;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;

/**
 * 内核写回宿主的**指令**。
 *
 * <p>这些只是「意图」，最终由宿主做安全裁决后执行：
 * 宿主会在应用前做一次兜底检查（例如授权不超过前车尾部、不超过进路终点），
 * 并且在人工驾驶（{@link Telemetry.TrainState#manuallyDriven()}）时不覆盖玩家操作。
 *
 * <p>安全方向约定（fail-safe）：**指令只能收紧、不能放宽**。
 * 宿主会把内核给出的 {@code speedCap} 与 Create 原生的 targetSpeed 取**较小值**，
 * 因此即便内核逻辑出错，也不会让列车比原版跑得更远。
 */
public final class Command {

    private Command() {}

    /**
     * 对一列车的控制指令。
     *
     * @param trainId           目标列车
     * @param direction         期望行驶方向
     * @param movementAuthority 距离授权：从车头起算，允许前进的最大距离（格）。
     *                          {@code <= 0} 等于「立即停车」。这是移动闭塞的核心量。
     * @param speedCap          绝对限速（格/tick）。与授权互相独立：
     *                          授权管「能走多远」，限速管「能走多快」。
     * @param hold              是否保持不动（停站、待避、信号未开放）
     * @param route             申请/更新进路；{@code null} 表示不改变现有进路
     */
    public record TrainCommand(
        UUID trainId,
        Direction direction,
        double movementAuthority,
        double speedCap,
        boolean hold,
        RouteRequest route
    ) {
        public static TrainCommand hold(UUID trainId) {
            return new TrainCommand(trainId, Direction.KEEP, 0, 0, true, null);
        }

        public static TrainCommand proceed(UUID trainId, double authority, double speedCap) {
            return new TrainCommand(trainId, Direction.KEEP, authority, speedCap, false, null);
        }
    }

    /** 行驶方向。用于「正线前进 / 倒车入库 / 换端」这类调度。 */
    public enum Direction {
        /** 维持当前朝向。 */
        KEEP,
        /** 按列车自身正向行驶。 */
        FORWARD,
        /** 倒车（仅双端车或有尾部驾驶台时有效）。 */
        REVERSE
    }

    /**
     * 进路申请。
     *
     * <p>宿主负责把它翻译成 Create 的 {@code navigation.currentPath}（一个
     * {@code List<Couple<TrackNode>>}）。**路径必须完整覆盖到目的地**：Create 的
     * {@code Navigation.navigateOptions} 在路径耗尽时会随手挑第一个分支
     * （{@code Navigation.java:362-372}），这是脱轨与挤岔的根源。
     *
     * @param destination 目的地节点（通常是车站所在边的一端）
     * @param path        有序边序列，从车头当前边开始，首尾相接
     * @param priority    进路优先级，用于多车竞争同一岔口时的仲裁
     * @param reason      可读原因（进 PIS / 日志）
     */
    public record RouteRequest(
        Rail.NodeId destination,
        List<Rail.EdgeId> path,
        RoutePriority priority,
        String reason
    ) {
        public RouteRequest {
            path = List.copyOf(path);
        }
    }

    /** 进路优先级。宿主按此排序处理同帧的多条申请。 */
    public enum RoutePriority {
        /** 紧急（救援、消防）。 */
        EMERGENCY(0),
        /** 正线客运。 */
        PASSENGER(1),
        /** 空车回送 / 调车。 */
        SHUNT(2),
        /** 待避：允许被更高优先级抢占。 */
        HOLD(3);

        private final int rank;

        RoutePriority(int rank) { this.rank = rank; }

        public int rank() { return rank; }

        public boolean outranks(RoutePriority other) { return this.rank < other.rank; }
    }

    /**
     * 对一台信号机的显示指令。
     *
     * <p>只传 **Aspect id**，不传灯位。具体亮哪几个灯由该信号机的
     * {@link top.hibermod.ctdapi.signal.SignalType} 决定——同一句「通行」在三显示机和四显示机上
     * 亮不同位置的灯，但内核说的是同一个语义。
     *
     * @param signalId 信号机 UUID
     * @param side     哪一面
     * @param aspectId 语义 id，必须是已注册且该机型支持的（见 {@code Topology.signalType(...)}）
     */
    public record AspectCommand(UUID signalId, Topology.SignalSide side, ResourceLocation aspectId) {}

    /**
     * 对岔口的锁闭指令。
     *
     * <p>注意：Create 的轨道在物理上**不会移动**，所以这里没有「扳道岔」这个动作，
     * 只有「锁闭某条支线」这一个语义。视觉上如需道岔摆动动画，由宿主单独实现
     * （那是一层装饰，不参与图）。
     *
     * <p>宿主若检测到真实的道岔实现（例如 Steam 'n' Rails 的 {@code TrackSwitch}），
     * 应把本指令**翻译成对它的调用**（`setSwitchState(NORMAL / REVERSE_LEFT / REVERSE_RIGHT)`），
     * 而不是自己维护一套并行的岔位——否则两套状态会互相打架。
     *
     * @param node     岔口
     * @param position 期望位置；{@code UNKNOWN} 表示"按 branch 推断"
     * @param branch   期望开通的股道边；{@code null} 表示解除锁闭
     * @param holder   锁的持有者（进路 id），用于冲突检测
     */
    public record SwitchCommand(Rail.NodeId node, Topology.SwitchPosition position,
                                Rail.EdgeId branch, long holder) {}
}
