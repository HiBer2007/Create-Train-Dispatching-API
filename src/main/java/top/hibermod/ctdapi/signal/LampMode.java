package top.hibermod.ctdapi.signal;

/**
 * 灯的显示方式。
 *
 * <p>闪烁不是「另一个颜色」，而是同一个灯座的时间行为——现实中黄闪、红闪都是独立灯语。
 */
public enum LampMode {
    /** 常亮。 */
    STEADY,

    /** 闪烁（默认 1 Hz，50% 占空比）。 */
    FLASHING,

    /** 脉冲（短促闪烁，用于特殊警示）。 */
    PULSED
}
