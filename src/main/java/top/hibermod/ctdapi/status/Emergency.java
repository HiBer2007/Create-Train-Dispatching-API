package top.hibermod.ctdapi.status;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.Rail;
import top.hibermod.ctdapi.Telemetry;

/**
 * **应急模式与应急指令**。
 *
 * <p>与 {@link Restrictions} 的区别：限制是「这段路不好走」，应急是「现在别按计划走了」。
 * 应急指令**压过调度**——内核收到后必须先服从，再谈优化。
 *
 * <h2>三级语义</h2>
 * <ol>
 *   <li>{@link Action#STOP_ALL} —— 全线/区域就地停车（火灾、地震、脱轨连锁）；</li>
 *   <li>{@link Action#EVACUATE} —— 就近站台停车并开门疏散；</li>
 *   <li>{@link Action#MANUAL_TAKEOVER} —— 交出控制权，ATO 与调度停止干预。</li>
 * </ol>
 * {@link Action#DEGRADED_RUN} 是降级继续运行（信号失效但线路可用），
 * {@link Action#RESUME} 解除应急。
 *
 * <h2>为什么应急要单独一张表</h2>
 * 应急是**有时限的临时状态**，而且必须能被审计：谁在什么时候因为什么下的令。
 * 混进调度引擎的私有状态里，出事之后没人说得清。
 */
public final class Emergency {

    private Emergency() {}

    /** 应急模式（由生效指令推导，不单独设置）。 */
    public enum Mode {
        /** 无应急。 */
        NORMAL,
        /** 有降级/局部指令，但未全线停车。 */
        ALERT,
        /** 有 STOP_ALL / EVACUATE 生效。 */
        EMERGENCY
    }

    /** 应急动作。 */
    public enum Action {
        /** 就地停车（不疏散）。 */
        STOP_ALL,
        /** 就近停车并开门疏散。 */
        EVACUATE,
        /** 转人工，自动系统全部让行。 */
        MANUAL_TAKEOVER,
        /** 降级继续运行（信号失效时的降级模式）。 */
        DEGRADED_RUN,
        /** 解除。 */
        RESUME
    }

    /**
     * 一条应急指令。
     *
     * @param id         实例 id
     * @param action     动作
     * @param edge       作用范围：某条边；{@code null} 表示全线
     * @param trainId    作用对象：某列车；{@code null} 表示范围内全部列车
     * @param issuedAt   下达时刻
     * @param validUntil 失效时刻；{@code Long.MAX_VALUE} 表示直到显式 {@link Action#RESUME}
     * @param issuer     下达者（玩家 UUID 或系统标识）
     * @param reason     原因（进 PIS 播报与审计日志）
     */
    public record Order(
        UUID id,
        Action action,
        @Nullable Rail.EdgeId edge,
        @Nullable UUID trainId,
        long issuedAt,
        long validUntil,
        long issuer,
        Component reason
    ) {
        public Order {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(reason, "reason");
        }

        public boolean activeAt(long now) {
            return now >= issuedAt && now < validUntil;
        }

        public boolean expiredAt(long now) {
            return now >= validUntil;
        }

        /** 是否作用于某列车。 */
        public boolean appliesTo(Telemetry.TrainState train) {
            if (trainId != null && !trainId.equals(train.id())) return false;
            if (edge != null) {
                return train.footprint().stream().anyMatch(s -> s.edge().equals(edge));
            }
            return true;
        }
    }

    // ── 注册表 ────────────────────────────────────────────────────────────

    private static final Map<UUID, Order> ACTIVE = new LinkedHashMap<>();

    public static Order issue(Order order) {
        if (ACTIVE.putIfAbsent(order.id(), order) != null) {
            throw new IllegalStateException("Emergency order already issued: " + order.id());
        }
        return order;
    }

    /**
     * 下达「解除」——把同范围内所有未失效的指令标记失效。
     *
     * @return 解除的条数
     */
    public static int resume(@Nullable Rail.EdgeId edge, @Nullable UUID trainId, long now) {
        int count = 0;
        for (Order o : ACTIVE.values()) {
            if (!o.activeAt(now)) continue;
            boolean sameEdge = edge == null || edge.equals(o.edge());
            boolean sameTrain = trainId == null || trainId.equals(o.trainId());
            if (sameEdge && sameTrain) {
                ACTIVE.put(o.id(), new Order(o.id(), o.action(), o.edge(), o.trainId(),
                    o.issuedAt(), now, o.issuer(), o.reason()));
                count++;
            }
        }
        return count;
    }

    public static boolean clear(UUID id) {
        return ACTIVE.remove(id) != null;
    }

    public static Optional<Order> get(UUID id) {
        return Optional.ofNullable(ACTIVE.get(id));
    }

    public static List<Order> active(long now) {
        List<Order> out = new ArrayList<>();
        for (Order o : ACTIVE.values()) {
            if (o.activeAt(now)) out.add(o);
        }
        return out;
    }

    /** 影响某列车的应急指令，按严重度排序（STOP_ALL / EVACUATE 在前）。 */
    public static List<Order> affecting(Telemetry.TrainState train, long now) {
        List<Order> out = new ArrayList<>();
        for (Order o : ACTIVE.values()) {
            if (o.activeAt(now) && o.appliesTo(train)) out.add(o);
        }
        out.sort((a, b) -> Integer.compare(severity(b.action()), severity(a.action())));
        return out;
    }

    /** 当前全局模式。 */
    public static Mode mode(long now) {
        Mode mode = Mode.NORMAL;
        for (Order o : ACTIVE.values()) {
            if (!o.activeAt(now)) continue;
            switch (o.action()) {
                case STOP_ALL, EVACUATE -> {
                    return Mode.EMERGENCY;
                }
                case MANUAL_TAKEOVER, DEGRADED_RUN -> mode = Mode.ALERT;
                default -> { }
            }
        }
        return mode;
    }

    /** 该车是否必须人工驾驶。 */
    public static boolean forcesManual(Telemetry.TrainState train, long now) {
        for (Order o : affecting(train, now)) {
            if (o.action() == Action.MANUAL_TAKEOVER) return true;
        }
        return false;
    }

    /** 该车是否必须立即停车。 */
    public static boolean forcesStop(Telemetry.TrainState train, long now) {
        for (Order o : affecting(train, now)) {
            if (o.action() == Action.STOP_ALL || o.action() == Action.EVACUATE) return true;
        }
        return false;
    }

    /** 该车是否被允许降级继续运行。 */
    public static boolean allowsDegradedRun(Telemetry.TrainState train, long now) {
        for (Order o : affecting(train, now)) {
            if (o.action() == Action.DEGRADED_RUN) return true;
        }
        return false;
    }

    public static int purge(long now) {
        int before = ACTIVE.size();
        ACTIVE.values().removeIf(o -> o.expiredAt(now));
        return before - ACTIVE.size();
    }

    public static Collection<Order> all() {
        return List.copyOf(ACTIVE.values());
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    /** 严重度，数字越大越紧急。 */
    public static int severity(Action action) {
        return switch (action) {
            case STOP_ALL -> 5;
            case EVACUATE -> 4;
            case MANUAL_TAKEOVER -> 3;
            case DEGRADED_RUN -> 2;
            case RESUME -> 0;
        };
    }

    /** 便捷：全线就地停车。 */
    public static Order stopAll(UUID id, long now, long issuer, Component reason) {
        return issue(new Order(id, Action.STOP_ALL, null, null, now, Long.MAX_VALUE, issuer, reason));
    }

    /** 便捷：某条边封闭并停车。 */
    public static Order stopEdge(UUID id, Rail.EdgeId edge, long now, long issuer, Component reason) {
        return issue(new Order(id, Action.STOP_ALL, edge, null, now, Long.MAX_VALUE, issuer, reason));
    }

    /** 便捷：某车转人工。 */
    public static Order manualTakeover(UUID id, UUID trainId, long now, long issuer, Component reason) {
        return issue(new Order(id, Action.MANUAL_TAKEOVER, null, trainId, now, Long.MAX_VALUE, issuer, reason));
    }

    /** 便捷：某边在某段时间内的原因说明，供 PIS 取用。 */
    @Nullable
    public static Component reasonFor(Rail.EdgeId edge, long now) {
        for (Order o : ACTIVE.values()) {
            if (o.activeAt(now) && edge.equals(o.edge())) return o.reason();
        }
        return null;
    }

    /** 未定义的原因常量，方便调用方避免拼写错误。 */
    public static final class Reasons {
        private Reasons() {}

        public static final ResourceLocation FIRE = id("fire");
        public static final ResourceLocation DERAILMENT = id("derailment");
        public static final ResourceLocation POWER_LOSS = id("power_loss");
        public static final ResourceLocation SIGNAL_FAILURE = id("signal_failure");
        public static final ResourceLocation OBSTRUCTION = id("obstruction");
        public static final ResourceLocation MAINTENANCE = id("maintenance");
        public static final ResourceLocation WEATHER = id("weather");
        public static final ResourceLocation CROWD = id("crowd");

        private static ResourceLocation id(String path) {
            return ResourceLocation.fromNamespaceAndPath("ctdapi", path);
        }
    }
}
