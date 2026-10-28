package top.hibermod.ctdapi.signal;

/**
 * 信号机的**联锁语义**——它对列车意味着什么。
 *
 * <p>这是整套注册 API 里最重要的部分。灯好看不重要，重要的是内核知道：
 * 「这台信号开放了，车能不能走」。
 *
 * <p>不同铁路的差异几乎全在这几个布尔量上，而不是在灯色上。
 *
 * @param target               它防护的范围
 * @param grantsAuthority      开放时是否授予行车授权（核心语义）
 * @param permissiveWhenClosed 关闭时是否允许低速越过（调车信号、引导信号）
 * @param stoppingPoint        是否是**停车点**（{@code false} = 预告机，列车不停）
 * @param defaultSpeedFraction 开放时的默认限速系数（0..1）
 */
public record SignalSemantics(
    TargetKind target,
    boolean grantsAuthority,
    boolean permissiveWhenClosed,
    boolean stoppingPoint,
    double defaultSpeedFraction
) {

    /** 信号机防护的对象。 */
    public enum TargetKind {
        /** 防护一条进路（进站/出站信号机）。 */
        ROUTE,
        /** 防护一个闭塞区间（区间通过信号机）。 */
        BLOCK,
        /** 防护一股站台轨道。 */
        PLATFORM,
        /** 防护整个车站（开放的进站信号 = 允许进入站内）。 */
        STATION,
        /** 防护调车区（调车信号机）。 */
        SHUNT_ZONE
    }

    /** 进站信号机：开放即允许进入车站，关闭时不能越过。 */
    public static final SignalSemantics HOME = new SignalSemantics(
        TargetKind.ROUTE, true, false, true, 1.0);

    /** 出站信号机：开放即允许进入区间。 */
    public static final SignalSemantics STARTING = new SignalSemantics(
        TargetKind.ROUTE, true, false, true, 1.0);

    /** 区间通过信号机：自动闭塞的一部分。 */
    public static final SignalSemantics BLOCK = new SignalSemantics(
        TargetKind.BLOCK, true, false, true, 1.0);

    /** 调车信号机：**关闭时也允许低速越过**（这是调车作业的基本前提）。 */
    public static final SignalSemantics SHUNT = new SignalSemantics(
        TargetKind.SHUNT_ZONE, true, true, true, 0.30);

    /**
     * 预告 / 复示信号机：**不是停车点**。
     *
     * <p>它只影响减速曲线，不产生授权边界。把它当边界会让列车提前停死在区间里。
     */
    public static final SignalSemantics DISTANT = new SignalSemantics(
        TargetKind.BLOCK, false, true, false, 1.0);

    /** 进路表示器：只显示方向，不产生任何约束。 */
    public static final SignalSemantics ROUTE_INDICATOR = new SignalSemantics(
        TargetKind.ROUTE, false, true, false, 1.0);
}
