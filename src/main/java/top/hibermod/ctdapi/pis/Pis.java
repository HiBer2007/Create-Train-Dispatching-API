package top.hibermod.ctdapi.pis;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * PIS（乘客信息系统）：**调度引擎发布渲染就绪的数据，宿主负责显示与对外推送**。
 *
 * <p>与 HUD 同一个思路：引擎可能跑在游戏外，所以这里只有数据，没有渲染。
 *
 * <h2>为什么要「渲染就绪」</h2>
 * Create 的 {@code GlobalTrainDisplayData} 只给了一组 {@code TrainDeparturePrediction}
 * （终点 + ETA tick），没有计划/实际双轴、没有站台、没有延误原因。
 * 真 PIS 需要的字段这里一次性定全，避免下游反复重查。
 *
 * <p>关键区分：
 * <ul>
 *   <li>{@link BoardEntry#scheduledTicks()} —— **权威计划**（图定时刻折算成「还有多少 tick」）；</li>
 *   <li>{@link BoardEntry#etaTicks()} —— **实时预测**；</li>
 *   <li>{@link BoardEntry#delayTicks()} —— 二者的差，可以有正负。</li>
 * </ul>
 * 没有前两个的分离，准点率就没有意义。
 */
public final class Pis {

    private Pis() {}

    /** 班次状态。 */
    public enum Status {
        /** 正常。 */
        ON_TIME,
        /** 晚点。 */
        DELAYED,
        /** 早点。 */
        EARLY,
        /** 已取消。 */
        CANCELLED,
        /** 停运/施工。 */
        SUSPENDED,
        /** 改道。 */
        DIVERTED,
        /** 加开。 */
        EXTRA
    }

    /** 紧急度，宿主据此排序或高亮。 */
    public enum Urgency {
        NORMAL,
        ATTENTION,
        CRITICAL
    }

    /**
     * 一块显示屏的声明。
     *
     * @param id        稳定标识（一块屏一个）
     * @param name      内部名（调试用）
     * @param station   所属车站 UUID；为 {@code null} 表示全线通用屏
     * @param maxRows   最多渲染几行
     * @param showVia   是否显示经由站
     */
    public record BoardSpec(
        ResourceLocation id,
        String name,
        @Nullable java.util.UUID station,
        int maxRows,
        boolean showVia
    ) {
        public BoardSpec {
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * 一条到站/发车信息。
     *
     * @param serviceCode    车次号（"G1234" / "S3-07"）
     * @param line           线路名（可为 {@code null}）
     * @param destination    终点/开往
     * @param via            经由（可为空列表）
     * @param scheduledTicks 图定还有多少 tick；未知为 -1
     * @param etaTicks       实时预测还有多少 tick；未知为 -1
     * @param delayTicks     延误（正数晚点）；未知为 0
     * @param platform       站台/股道名（可为 {@code null}）
     * @param status         状态
     * @param note           附加说明（延误原因等，可为 {@code null}）
     * @param urgency        紧急度
     */
    public record BoardEntry(
        String serviceCode,
        @Nullable Component line,
        Component destination,
        List<Component> via,
        int scheduledTicks,
        int etaTicks,
        int delayTicks,
        @Nullable Component platform,
        Status status,
        @Nullable Component note,
        Urgency urgency,
        @Nullable ResourceLocation signalSystemId
    ) {
        public BoardEntry {
            Objects.requireNonNull(serviceCode, "serviceCode");
            Objects.requireNonNull(destination, "destination");
            status = status == null ? Status.ON_TIME : status;
            urgency = urgency == null ? Urgency.NORMAL : urgency;
            via = via == null ? List.of() : List.copyOf(via);
        }

        /**
         * 不带来源系统的便捷构造（老代码可用）。
         *
         * <p>但**正点率与晚点预计是信号系统的 PIS 引擎算出来的**，渲染方需要知道
         * 这条预计来自哪套制度，所以在能标注时请用完整构造器。
         */
        public BoardEntry(String serviceCode, @Nullable Component line, Component destination,
                          List<Component> via, int scheduledTicks, int etaTicks, int delayTicks,
                          @Nullable Component platform, Status status, @Nullable Component note,
                          Urgency urgency) {
            this(serviceCode, line, destination, via, scheduledTicks, etaTicks, delayTicks,
                platform, status, note, urgency, null);
        }

        /** 是否可显示具体分钟数（两头都未知时只能显示「—」）。 */
        public boolean hasEta() {
            return etaTicks >= 0;
        }

        /** 计划与实时的差是否可信。 */
        public boolean hasSchedule() {
            return scheduledTicks >= 0 && etaTicks >= 0;
        }
    }

    /**
     * 一块屏的一帧内容。
     *
     * @param boardId 对应 {@link BoardSpec#id()}
     * @param revision 单调递增的版本号；外部系统可据此做增量拉取
     * @param header  屏头（可为 {@code null}，宿主用车站名补）
     */
    public record BoardModel(
        ResourceLocation boardId,
        long revision,
        @Nullable Component header,
        List<BoardEntry> entries
    ) {
        public BoardModel {
            Objects.requireNonNull(boardId, "boardId");
            entries = List.copyOf(entries);
        }
    }

    /**
     * 车内 / 在线乘客视图：当前这班车的简要信息。
     *
     * @param serviceCode 车次
     * @param nextStop    下一站
     * @param terminal    终点
     * @param etaTicks    到下一站的预计 tick
     * @param delayTicks  当前晚点
     * @param state       自由文本状态（"车门开启" / "正在通过" / "临时停车"）
     */
    public record ServiceInfo(
        String serviceCode,
        Component nextStop,
        Component terminal,
        int etaTicks,
        int delayTicks,
        @Nullable Component state
    ) {}

    // ── 注册表 ────────────────────────────────────────────────────────────

    private static final Map<ResourceLocation, BoardSpec> BOARDS = new LinkedHashMap<>();
    private static boolean frozen;

    public static BoardSpec register(BoardSpec spec) {
        if (frozen) {
            throw new IllegalStateException("PIS registry is frozen; register during mod init instead");
        }
        if (BOARDS.putIfAbsent(spec.id(), spec) != null) {
            throw new IllegalStateException("Duplicate PIS board id: " + spec.id());
        }
        return spec;
    }

    public static Optional<BoardSpec> board(ResourceLocation id) {
        return Optional.ofNullable(BOARDS.get(id));
    }

    public static Collection<BoardSpec> boards() {
        return BOARDS.values();
    }

    public static Collection<BoardSpec> boardsOf(java.util.UUID station) {
        return BOARDS.values().stream()
            .filter(b -> station.equals(b.station()))
            .toList();
    }

    public static boolean frozen() {
        return frozen;
    }

    public static void freeze() {
        frozen = true;
    }

    public static void resetForTests() {
        BOARDS.clear();
        frozen = false;
    }
}
