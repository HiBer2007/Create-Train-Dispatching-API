package top.hibermod.ctdapi;

import java.util.Comparator;
import java.util.Objects;

import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 铁路图的**稳定标识**。
 *
 * <p>设计约束：{@code com.simibubi.create.content.trains.graph.TrackNode} 与 {@code TrackEdge}
 * 都是短生命周期对象——轨道被拆改一次，对象就被重建。第三方调度内核绝不能持有它们。
 * 因此本 API 只暴露**值语义**的标识：
 *
 * <ul>
 *   <li>{@link NodeId} 对齐 Create 的 {@code TrackNodeLocation} 打包方式：水平 **半格** 量化、
 *       垂直按 1/16 像素偏移。同一物理坐标在任何一次加载后都得到相同的 {@code NodeId}。</li>
 *   <li>{@link EdgeId} 是**无向**的：{@code (a,b)} 与 {@code (b,a)} 规范化后相等，与 Create
 *       内部「一条边存两遍」的实现无关。</li>
 *   <li>{@link Span} 用「沿边的距离」表达一段连续区间，是实现移动闭塞的基本单位。</li>
 * </ul>
 *
 * <p>⚠ 跨维度边的 {@code length()} 在 Create 中恒为 0。{@link Span} 的 {@code from/to} 对这类边
 * 没有意义，宿主会跳过它们并把跨维度连接单独上报（见 {@link Topology.Edge#interDimensional()}）。
 */
public final class Rail {

    private Rail() {}

    /**
     * 一个轨道节点的稳定标识。
     *
     * @param dimension    所在维度
     * @param x2           世界坐标 x 的**半格**值（{@code floor(round(x*2))}）
     * @param y2           世界坐标 y 的**半格**值（Create 内部即 {@code floor(y)*2}）
     * @param z2           世界坐标 z 的**半格**值
     * @param yOffsetPixels 垂直微调，1/16 格为单位（距道床的抬升）
     */
    public record NodeId(ResourceKey<Level> dimension, int x2, int y2, int z2, int yOffsetPixels)
        implements Comparable<NodeId> {

        public NodeId {
            Objects.requireNonNull(dimension, "dimension");
        }

        /** 对应的世界坐标（与 {@code TrackNodeLocation#getLocation()} 一致）。 */
        public double x() { return x2 / 2.0; }
        public double y() { return y2 / 2.0 + yOffsetPixels / 16.0; }
        public double z() { return z2 / 2.0; }

        private static final Comparator<NodeId> ORDER =
            Comparator.comparing((NodeId n) -> n.dimension().location().toString())
                .thenComparingInt(NodeId::x2)
                .thenComparingInt(NodeId::y2)
                .thenComparingInt(NodeId::z2)
                .thenComparingInt(NodeId::yOffsetPixels);

        @Override
        public int compareTo(NodeId o) { return ORDER.compare(this, o); }

        // ── 字符串编解码 ──────────────────────────────────────────────────

        /**
         * 稳定字符串形式：{@code namespace:path@x2,y2,z2,offset}。
         *
         * <p>用于脚本、配置、日志与网络传输——这些场合都没法传 Java 对象。
         * 格式一旦发布**不可更改**：改了会让存档里的脚本配置全部失联。
         */
        public String encode() {
            return dimension.location()
                + "@" + x2 + "," + y2 + "," + z2 + "," + yOffsetPixels;
        }

        /**
         * 解析 {@link #encode()} 的产物。
         *
         * @throws IllegalArgumentException 格式不合法
         */
        public static NodeId decode(String s) {
            if (s == null) throw new IllegalArgumentException("node id is null");
            int at = s.lastIndexOf('@');
            if (at <= 0) throw new IllegalArgumentException("missing '@' in node id: " + s);

            ResourceLocation dim = ResourceLocation.tryParse(s.substring(0, at));
            if (dim == null) throw new IllegalArgumentException("bad dimension in node id: " + s);

            String[] parts = s.substring(at + 1).split(",");
            if (parts.length != 4) {
                throw new IllegalArgumentException("expected 4 coordinates in node id: " + s);
            }
            try {
                return new NodeId(
                    ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim),
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("bad number in node id: " + s, e);
            }
        }

        /** 宽松解析：格式不合法时返回 {@code null}，不抛异常。 */
        public static NodeId tryDecode(String s) {
            try {
                return decode(s);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * 一条轨道边的稳定标识（**无向**）。
     *
     * <p>规范化规则：端点按 {@link NodeId#compareTo} 排序，因此 {@code EdgeId.of(a,b).equals(EdgeId.of(b,a))}。
     * 内核若需要方向语义，请用 {@link Span} 的 {@code from/to} 或 {@link Topology.Edge#length()} 自行处理。
     */
    public record EdgeId(NodeId first, NodeId second) implements Comparable<EdgeId> {

        public EdgeId {
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
        }

        /** 构造规范化的边标识。 */
        public static EdgeId of(NodeId a, NodeId b) {
            return a.compareTo(b) <= 0 ? new EdgeId(a, b) : new EdgeId(b, a);
        }

        /** 该边的另一端。若 {@code endpoint} 不在这条边上则返回 {@code null}。 */
        public NodeId other(NodeId endpoint) {
            if (endpoint.equals(first)) return second;
            if (endpoint.equals(second)) return first;
            return null;
        }

        public boolean touches(NodeId node) {
            return first.equals(node) || second.equals(node);
        }

        @Override
        public int compareTo(EdgeId o) {
            int c = first.compareTo(o.first);
            return c != 0 ? c : second.compareTo(o.second);
        }

        // ── 字符串编解码 ──────────────────────────────────────────────────

        /**
         * 稳定字符串形式：{@code <node>|<node>}。
         *
         * <p>{@code |} 不会出现在 {@code ResourceLocation} 或坐标里，可以安全当分隔符。
         */
        public String encode() {
            return first.encode() + "|" + second.encode();
        }

        /**
         * 解析 {@link #encode()} 的产物。
         *
         * @throws IllegalArgumentException 格式不合法
         */
        public static EdgeId decode(String s) {
            if (s == null) throw new IllegalArgumentException("edge id is null");
            int bar = s.indexOf('|');
            if (bar <= 0 || bar == s.length() - 1) {
                throw new IllegalArgumentException("expected '<node>|<node>' in edge id: " + s);
            }
            return new EdgeId(
                NodeId.decode(s.substring(0, bar)),
                NodeId.decode(s.substring(bar + 1)));
        }

        /** 宽松解析：格式不合法时返回 {@code null}。 */
        public static EdgeId tryDecode(String s) {
            try {
                return decode(s);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /**
     * 一条边上的连续区间，用于表达列车占用、授权范围、限速区段。
     *
     * @param edge 所属边
     * @param from 起点距离（沿 {@code edge.first()} → {@code edge.second()} 方向，单位：格）
     * @param to   终点距离，必须 {@code >= from}
     */
    public record Span(EdgeId edge, double from, double to) {

        public Span {
            Objects.requireNonNull(edge, "edge");
            if (to < from) {
                throw new IllegalArgumentException("Span.to(" + to + ") < Span.from(" + from + ") on " + edge);
            }
        }

        public double length() { return to - from; }

        public boolean overlaps(Span other) {
            return edge.equals(other.edge) && from < other.to && other.from < to;
        }

        public boolean touchesOrOverlaps(Span other) {
            return edge.equals(other.edge) && from <= other.to && other.from <= to;
        }

        /** 与另一区间求交集；无交集返回 {@code null}。 */
        public Span intersect(Span other) {
            if (!edge.equals(other.edge)) return null;
            double f = Math.max(from, other.from);
            double t = Math.min(to, other.to);
            return t > f ? new Span(edge, f, t) : null;
        }

        public Span shift(double delta) { return new Span(edge, from + delta, to + delta); }

        /** 收缩到 {@code [0, length]} 范围内，用于几何越界修正。 */
        public Span clamp(double edgeLength) {
            double f = Math.max(0, Math.min(from, edgeLength));
            double t = Math.max(f, Math.min(to, edgeLength));
            return new Span(edge, f, t);
        }
    }
}
