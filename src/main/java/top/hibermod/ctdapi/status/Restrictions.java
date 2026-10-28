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

/**
 * **临时限制**：封路、施工、限速、单线行车。
 *
 * <p>与 {@link Emergency} 的区别：
 * <ul>
 *   <li>{@code Restrictions} 是**线路属性**——这段路暂时不能走 / 只能慢走；</li>
 *   <li>{@code Emergency} 是**运营指令**——就地停车 / 疏散 / 人工接管。</li>
 * </ul>
 * 前者会被调度引擎当作「可绕行的障碍」，后者会直接压过调度。
 *
 * <h2>谁写谁读</h2>
 * 宿主（或管理员的命令）发布；调度引擎每帧读 {@link #active(long)} 参与进路计算。
 * **调度引擎不要自己写这个表**——它决定的是"怎么走"，不是"哪里不能走"。
 *
 * <h2>为什么带时间窗</h2>
 * 夜间施工、天窗点、临时封路都有起止时间。没有时间窗，一条限制就得靠人记得来撤销，
 * 忘一次就是永久停运。
 */
public final class Restrictions {

    private Restrictions() {}

    /** 限制种类。 */
    public enum Kind {
        /** 完全封闭，禁止通行。 */
        CLOSED,
        /** 限速通过。 */
        SPEED_LIMIT,
        /** 单线行车（双向共用，需要交会）。 */
        SINGLE_TRACK,
        /** 注意运行（无硬限速，但调度应降低优先级）。 */
        CAUTION,
        /** 施工作业区。 */
        WORK_ZONE
    }

    /**
     * 一条限制。
     *
     * @param id        实例 id，撤销时用
     * @param kind      种类
     * @param edge      所在的边
     * @param from      起（沿边规范方向的距离）
     * @param to        止
     * @param speedLimit 限速（格/tick），仅 {@link Kind#SPEED_LIMIT} 有意义
     * @param validFrom 生效时刻（游戏刻）
     * @param validUntil 失效时刻；{@code Long.MAX_VALUE} 表示不自动失效
     * @param reasonId  原因类型 id（"ctdapi:maintenance" / "ctdapi:derailment"…），供 PIS 播报
     * @param note      人读说明
     * @param issuer    发布者（玩家 UUID 或系统标识的哈希）
     */
    public record Restriction(
        UUID id,
        Kind kind,
        Rail.EdgeId edge,
        double from,
        double to,
        double speedLimit,
        long validFrom,
        long validUntil,
        ResourceLocation reasonId,
        Component note,
        long issuer
    ) {
        public Restriction {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(edge, "edge");
            Objects.requireNonNull(note, "note");
            if (to < from) {
                double t = from;
                from = to;
                to = t;
            }
        }

        public boolean hasSpeedLimit() {
            return kind == Kind.SPEED_LIMIT && speedLimit > 0;
        }

        public boolean activeAt(long now) {
            return now >= validFrom && now < validUntil;
        }

        /** 覆盖边上某个距离点。 */
        public boolean covers(double distance) {
            return distance >= from && distance <= to;
        }

        public boolean expiredAt(long now) {
            return now >= validUntil;
        }

        public static Restriction closed(UUID id, Rail.EdgeId edge, double from, double to,
                                         long now, long until, ResourceLocation reason, Component note) {
            return new Restriction(id, Kind.CLOSED, edge, from, to, 0, now, until, reason, note, 0L);
        }

        public static Restriction speedLimit(UUID id, Rail.EdgeId edge, double from, double to,
                                             double limit, long now, long until,
                                             ResourceLocation reason, Component note) {
            return new Restriction(id, Kind.SPEED_LIMIT, edge, from, to, limit, now, until, reason, note, 0L);
        }
    }

    // ── 注册表 ────────────────────────────────────────────────────────────

    private static final Map<UUID, Restriction> ACTIVE = new LinkedHashMap<>();

    /** 发布一条限制。重复 id 抛异常（避免静默覆盖掉别人的封路）。 */
    public static Restriction publish(Restriction restriction) {
        if (ACTIVE.putIfAbsent(restriction.id(), restriction) != null) {
            throw new IllegalStateException("Restriction already published: " + restriction.id());
        }
        return restriction;
    }

    /** 撤销。返回是否存在过。 */
    public static boolean revoke(UUID id) {
        return ACTIVE.remove(id) != null;
    }

    public static Optional<Restriction> get(UUID id) {
        return Optional.ofNullable(ACTIVE.get(id));
    }

    /** 全部当前生效的限制（已按时间过滤）。 */
    public static List<Restriction> active(long now) {
        List<Restriction> out = new ArrayList<>();
        for (Restriction r : ACTIVE.values()) {
            if (r.activeAt(now)) out.add(r);
        }
        return out;
    }

    /** 某条边上当前生效的限制。 */
    public static List<Restriction> onEdge(Rail.EdgeId edge, long now) {
        List<Restriction> out = new ArrayList<>();
        for (Restriction r : ACTIVE.values()) {
            if (r.edge().equals(edge) && r.activeAt(now)) out.add(r);
        }
        return out;
    }

    /** 边上某个距离点是否被封闭。 */
    public static boolean isBlocked(Rail.EdgeId edge, double distance, long now) {
        for (Restriction r : ACTIVE.values()) {
            if (r.kind() == Kind.CLOSED && r.edge().equals(edge)
                && r.activeAt(now) && r.covers(distance)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 清理已过期的限制。宿主应当定期调用（比如每秒一次），避免表无限增长。
     *
     * @return 清掉的条数
     */
    public static int purge(long now) {
        int before = ACTIVE.size();
        ACTIVE.values().removeIf(r -> r.expiredAt(now));
        return before - ACTIVE.size();
    }

    /** 全部条目（含未生效/已过期），供调试与持久化。 */
    public static Collection<Restriction> all() {
        return List.copyOf(ACTIVE.values());
    }

    public static void clear() {
        ACTIVE.clear();
    }

    /** 某条限制是否影响给定的边集合（宿主用它裁掉无关条目）。 */
    public static List<Restriction> affecting(Collection<Rail.EdgeId> edges, long now) {
        List<Restriction> out = new ArrayList<>();
        for (Restriction r : ACTIVE.values()) {
            if (r.activeAt(now) && edges.contains(r.edge())) out.add(r);
        }
        return out;
    }

    /** 取某条限制的人读描述，供 PIS / HUD 使用。 */
    @Nullable
    public static Component describe(UUID id) {
        Restriction r = ACTIVE.get(id);
        return r == null ? null : r.note();
    }
}
