package top.hibermod.ctdapi;

import java.util.Collection;
import java.util.List;

import top.hibermod.ctdapi.signal.Aspect;
import top.hibermod.ctdapi.signal.SignalRegistries;
import top.hibermod.ctdapi.signal.SignalType;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 铁路拓扑的**只读快照**，由宿主在每个调度帧提供给内核。
 *
 * <p>内核不得持有快照中的对象跨帧使用——拓扑变化时宿主会递增 {@link #version()} 并下发
 * {@link TopologyDelta}。跨帧缓存必须以 {@code version} 为失效键。
 *
 * <p>本模型刻意**不暴露** Create 的任何类型。宿主负责在 Create 的 {@code TrackGraph}
 * 与这份模型之间做双向适配。
 */
public interface Topology {

    /** 拓扑版本号。任何节点/边/信号/车站的增删改都会递增。 */
    long version();

    // ── 基础图 ────────────────────────────────────────────────────────────

    Collection<Node> nodes();

    /** 不存在时返回 {@code null}。 */
    Node node(Rail.NodeId id);

    /** 从该节点出发的所有边。岔口返回 3 条以上。 */
    Collection<Edge> edgesFrom(Rail.NodeId id);

    Collection<Edge> edges();

    /** 不存在时返回 {@code null}。 */
    Edge edge(Rail.EdgeId id);

    // ── 设施 ──────────────────────────────────────────────────────────────

    Collection<SignalSite> signals();

    Collection<SwitchSite> switches();

    Collection<StationSite> stations();

    /** 逻辑区段（闭塞分区 / 轨道电路）。由宿主根据标注与信号位置切分出来。 */
    Collection<Section> sections();

    /** 包含给定边（或其中一段）的所有区段。 */
    Collection<Section> sectionsOf(Rail.EdgeId edge);

    /**
     * 物理上全部列车最长可达长度（格）。用于判定「一段侧线能否容下一列车」。
     * 由主机配置给出，不随列车变化。
     */
    double maxTrainLength();

    // ── 记录 ──────────────────────────────────────────────────────────────

    /**
     * 一个轨道节点。
     *
     * @param degree 连接数。Create 中没有「道岔」实体：{@code degree >= 3} 的节点**就是**岔口。
     */
    record Node(Rail.NodeId id, int degree, boolean isSwitch) {}

    /**
     * 一条轨道边。
     *
     * @param length           边长（格）。跨维度边为 0。
     * @param interDimensional 是否跨维度
     * @param isTurn           是否是贝塞尔弯道（曲率不均匀，位置换算需用弧长参数化）
     */
    record Edge(
        Rail.EdgeId id,
        double length,
        boolean interDimensional,
        boolean isTurn,
        String trackMaterialId
    ) {}

    /**
     * 一台信号机（信号头）。
     *
     * <p>Create 原生的 `SignalBoundary` 一台机器**两面**，每面一个闭塞分区。
     * 我们的模型保留这个双面结构（因为轨道图本身是双向的），但把「灯」交给
     * {@link Aspect} 表达。
     *
     * @param id            稳定 UUID（{@code SignalBoundary.id}，或自研信号机的边点 UUID）
     * @param edge          所在边
     * @param distance      沿 {@code edge.first()} → {@code edge.second()} 的位置
     * @param hasFirstFace  该机在「面向 first 端」是否有灯头
     * @param hasSecondFace 该机在「面向 second 端」是否有灯头
     * @param crossSignal   是否是交叉信号（一机管多股道）
     * @param kind          信号机用途分类，影响联锁如何对待它
     * @param lampCount     每个灯头的灯座数量（3 = 三显示，4 = 四显示，1 = 单灯）
     * @param label         信号机编号/代号（如 "X101"），可为空
     */
    record SignalSite(
        java.util.UUID id,
        Rail.EdgeId edge,
        double distance,
        boolean hasFirstFace,
        boolean hasSecondFace,
        boolean crossSignal,
        ResourceLocation typeId,
        String label
    ) {
        public boolean hasFace(Topology.SignalSide side) {
            return side == SignalSide.FIRST ? hasFirstFace : hasSecondFace;
        }

        /** 该机的机型定义；未注册时为回退机型。 */
        public SignalType type() {
            return SignalRegistries.typeOrFallback(typeId);
        }

        /** 该灯头能否表达某个语义（机型没有对应灯位就显示不出来）。 */
        public boolean canShow(Aspect aspect) {
            return type().supports(aspect.id());
        }
    }

    /** 信号机朝向：{@code FIRST} = 面向 {@code edge.first()} 端来车。 */
    enum SignalSide { FIRST, SECOND }

    // ── 信号规格注册表的只读视图 ────────────────────────────────────────

    /** 全部已注册的信号机型。 */
    Collection<SignalType> signalTypes();

    /** 按 id 取机型；未注册返回 {@code null}。 */
    SignalType signalType(ResourceLocation id);

    /** 全部已注册的信号语义。 */
    Collection<Aspect> aspects();

    /** 按 id 取语义；未注册返回 {@code null}。 */
    Aspect aspect(ResourceLocation id);

    /**
     * 一个岔口。Create 的轨道**不会**平移，所以这里的「定位」是纯逻辑概念：
     * 它表示「哪一条支线当前被进路锁闭」。
     *
     * @param node     岔口节点
     * @param branches 从该节点出发的全部边（顺序稳定，见 {@code normalBranch}）
     * @param normal   最顺直的那条支线（{@link #branches()} 中的下标）。玩家未标注时由几何自动推定。
     */
    record SwitchSite(Rail.NodeId node, List<Rail.EdgeId> branches, int normal) {
        public Rail.EdgeId branch(int index) { return branches.get(index); }
    }

    /**
     * 一个车站（Create 的 {@code GlobalStation}）。
     *
     * @param assembled 正在装配中：此时禁止任何进路通过该站
     */
    record StationSite(
        java.util.UUID id,
        Component name,
        Rail.EdgeId edge,
        double distance,
        boolean assembled,
        List<Platform> platforms
    ) {}

    /**
     * 一个可停靠站台。
     *
     * @param spans            可停靠的连续区间（通常 1 段，跨边时多段）
     * @param maxTrainLength   该站台能容纳的最长列车（格）
     * @param lateralPlatform  站台在轨道哪一侧（用于 PIS 播报「X 侧车门」），未知为 0
     */
    record Platform(List<Rail.Span> spans, double maxTrainLength, int lateralPlatform) {}

    /**
     * 一个逻辑区段。
     *
     * <p>{@link SectionKind} 必须由玩家标注——Create 没有任何「正线/侧线」概念，
     * 无法从拓扑推断。宿主提供标注工具，结果存进自己的 SavedData。
     */
    record Section(
        SectionId id,
        SectionKind kind,
        List<Rail.Span> spans,
        double length,
        String displayName,
        java.util.UUID owningStation
    ) {
        /** 该区段能否容纳一列长度为 {@code trainLength} 的车。 */
        public boolean canHold(double trainLength) { return length >= trainLength; }
    }

    /** 区段的稳定标识（由宿主分配，按 {@link Rail.Span} 列表的规范化哈希）。 */
    record SectionId(int value) {}

    /** 区段类别。 */
    enum SectionKind {
        /** 正线：主线股道，优先通行。 */
        MAIN,
        /** 侧线：会让/待避用的股道。 */
        SIDING,
        /** 站线：站台所在股道。 */
        PLATFORM,
        /** 其他（存车线、检修线、尽头线）。 */
        OTHER
    }

    /**
     * 拓扑增量。内核应据此失效自己的缓存，而不是每帧全量重建。
     *
     * @param addedNodes   新增节点
     * @param removedNodes 移除节点（连带所有涉及它们的边）
     * @param addedEdges   新增边
     * @param removedEdges 移除边
     * @param structureBroke 是否发生了「图被拆开」这类结构性变化（此时建议全量重建）
     */
    record TopologyDelta(
        Collection<Rail.NodeId> addedNodes,
        Collection<Rail.NodeId> removedNodes,
        Collection<Rail.EdgeId> addedEdges,
        Collection<Rail.EdgeId> removedEdges,
        boolean structureBroke
    ) {
        public boolean isEmpty() {
            return !structureBroke
                && addedNodes.isEmpty() && removedNodes.isEmpty()
                && addedEdges.isEmpty() && removedEdges.isEmpty();
        }
    }
}
