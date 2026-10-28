package top.hibermod.ctdapi.ato;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.KernelEnv;
import top.hibermod.ctdapi.Rail;
import top.hibermod.ctdapi.Telemetry;
import top.hibermod.ctdapi.status.Emergency;
import top.hibermod.ctdapi.status.Restrictions;
import top.hibermod.ctdapi.traction.Traction;

/**
 * **ATO 引擎**：把「距离授权 + 信号显示」翻译成「给多少牵引、多少制动」。
 *
 * <h2>铁律：一个 ATO 对应一套信号制度</h2>
 * ATO 的制动曲线必须遵守它所在信号制度的显示逻辑——四显示自动闭塞和调车场调车信号，
 * 同样的授权距离对应的操纵完全不同。所以 {@link #signalSystemId()} 是**必填且唯一**的：
 *
 * <ul>
 *   <li>一个 ATO 只服务一套信号制度；</li>
 *   <li>同一个信号引擎可以没有 ATO——此时该制式下**不能启动自动驾驶**，
 *       只能人工驾驶或由信号引擎给出建议速度（见 {@link AtoEngines#check}）；</li>
 *   <li>一列车可以同时装备多套信号系统的车载设备（见 {@link Telemetry.TrainState#signalSystems()}），
 *       但任一时刻只用一套，用哪套由**进路所属交路**决定。</li>
 * </ul>
 *
 * <h2>ATO 不决定「能不能走」</h2>
 * 授权由信号引擎给。ATO 只在授权之内优化操纵：平稳加减速、停车对位、开关门、
 * 低于余量时提前请求补给。**任何情况下 ATO 都不得把车开出授权范围。**
 */
public interface AtoEngine {

    /** 稳定标识。 */
    ResourceLocation id();

    /** ★ 绑定的信号制度。必须与 {@link top.hibermod.ctdapi.signal.SignalSystem#id()} 一致。 */
    ResourceLocation signalSystemId();

    /** 挂载。此时 {@code env.state()} 已恢复，可用于保存 ATO 自己的学习参数。 */
    default void onAttach(KernelEnv env) {}

    /** 卸载。 */
    default void onDetach() {}

    /**
     * 每帧一次的决策。**必须是无副作用的纯函数**——宿主可能因为异常重试同一帧。
     *
     * @param in 本帧上下文
     * @return 操纵指令；返回 {@code null} 等价于「本帧不干预」
     */
    @Nullable
    Decision decide(Context in);

    // ── 上下文 ────────────────────────────────────────────────────────────

    /**
     * ATO 的一帧输入。
     *
     * @param gameTime         服务端游戏刻
     * @param train            列车实时状态
     * @param power            牵引能量状态（燃料 / 电量 / 受电状态）
     * @param speedClass       本趟车次的速度等级；未知时为 {@code null}（此时按授权与信号限速）
     * @param authority        当前距离授权（格）
     * @param permittedSpeed   信号侧给出的限速（格/tick），已含信号机限速
     * @param distanceToTarget 到当前停车点的距离（格）；无停车点为 {@link Double#MAX_VALUE}
     * @param targetName       停车点名称（站名 / 信号机编号），可为 {@code null}
     * @param atPlatform       是否已停靠站台（开门条件之一）
     * @param doorsOpen        车门当前是否开启
     * @param restrictions     影响本车前方区段的临时限制（限速、封路、施工）
     * @param emergencies      影响本车的应急指令
     */
    record Context(
        long gameTime,
        Telemetry.TrainState train,
        Traction.PowerState power,
        @Nullable Traction.SpeedClass speedClass,
        double authority,
        double permittedSpeed,
        double distanceToTarget,
        @Nullable Component targetName,
        boolean atPlatform,
        boolean doorsOpen,
        List<Restrictions.Restriction> restrictions,
        List<Emergency.Order> emergencies
    ) {
        public Context {
            restrictions = restrictions == null ? List.of() : List.copyOf(restrictions);
            emergencies = emergencies == null ? List.of() : List.copyOf(emergencies);
        }

        /** 前方是否有导致停车的限制（封路 / 中断）。 */
        public boolean blockedAhead() {
            return restrictions.stream().anyMatch(r -> r.kind() == Restrictions.Kind.CLOSED);
        }

        /** 前方最严格的限速；无限制时返回 {@link Double#MAX_VALUE}。 */
        public double strictestLimit() {
            double min = Double.MAX_VALUE;
            for (Restrictions.Restriction r : restrictions) {
                if (r.hasSpeedLimit()) min = Math.min(min, r.speedLimit());
            }
            return min;
        }

        /** 本车是否被应急指令接管。 */
        public boolean underEmergency() {
            return !emergencies.isEmpty();
        }

        /** 还能不能牵引（没电/没油时 ATO 必须请求补给）。 */
        public boolean canTraction() {
            return power.canTraction();
        }
    }

    // ── 输出 ──────────────────────────────────────────────────────────────

    /** 车门动作。 */
    enum DoorAction {
        /** 不改变。 */
        NONE,
        /** 开门（ATP 与站台对位都满足才执行）。 */
        OPEN,
        /** 关门。 */
        CLOSE
    }

    /**
     * 一次补给请求。
     *
     * @param serviceId 补什么（加燃料 / 充电 / 上水 / 清空），由设备实现解释
     * @param deviceId  目标设备实例；{@code null} 表示「让宿主就近挑一个」
     */
    record ServiceRequest(ResourceLocation serviceId, @Nullable UUID deviceId) {}

    /**
     * 一帧的操纵决策。
     *
     * @param traction    牵引档位 0..1
     * @param brake       制动档位 0..1
     * @param targetSpeed 目标速度（格/tick）。宿主的执行是
     *                    {@code min(原生意图, 本值, 授权允许速度, 限速)}
     * @param doors       车门动作
     * @param hold        是否保持停车（ATP 未放行 / 应急）
     * @param service     补给请求；{@code null} 表示不需要
     * @param reason      一句话说明，进 HUD 与日志。**不要留空**，排障全靠它
     */
    record Decision(
        double traction,
        double brake,
        double targetSpeed,
        DoorAction doors,
        boolean hold,
        @Nullable ServiceRequest service,
        Component reason
    ) {
        public Decision {
            doors = doors == null ? DoorAction.NONE : doors;
            traction = clamp01(traction);
            brake = clamp01(brake);
            if (targetSpeed < 0) targetSpeed = 0;
        }

        /** 惰行：不牵引也不制动。 */
        public static Decision coast(String why) {
            return new Decision(0, 0, Double.MAX_VALUE, DoorAction.NONE, false, null, Component.literal(why));
        }

        /** 全制动停车。 */
        public static Decision stop(String why) {
            return new Decision(0, 1, 0, DoorAction.NONE, true, null, Component.literal(why));
        }

        private static double clamp01(double v) {
            return v < 0 ? 0 : (v > 1 ? 1 : v);
        }
    }

    /**
     * ATO 可用性判定结果。
     *
     * @param available 能否启动自动驾驶
     * @param reason    不可用的原因（可用时为空）
     */
    record Availability(boolean available, Component reason) {
        public static Availability ok() {
            return new Availability(true, Component.empty());
        }

        public static Availability no(String why) {
            return new Availability(false, Component.literal(why));
        }
    }

    /** 便捷：本引擎能否用于某列车（按该车的车载设备能力判断）。 */
    default Availability availabilityFor(Collection<ResourceLocation> trainSignalSystems) {
        if (trainSignalSystems == null || !trainSignalSystems.contains(signalSystemId())) {
            return Availability.no(
                "train has no onboard equipment for signal system " + signalSystemId());
        }
        return Availability.ok();
    }

    /** 便捷：本引擎是否覆盖某条进路的边（用于「这趟车现在归谁管」的判断）。 */
    default boolean covers(Rail.EdgeId ignored) {
        return true;
    }
}
