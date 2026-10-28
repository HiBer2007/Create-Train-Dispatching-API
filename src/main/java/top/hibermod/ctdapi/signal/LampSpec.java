package top.hibermod.ctdapi.signal;

/**
 * 一个灯座的规格：颜色 + 显示方式。
 *
 * <p>灯座的**顺序**由它在 {@link SignalType#lampLayout()} 里的下标决定，
 * 这个下标也是 {@link Aspect#litSlots()} 引用的下标。
 *
 * @param color 灯色
 * @param mode  常亮 / 闪烁 / 脉冲
 */
public record LampSpec(LampColor color, LampMode mode) {

    public static LampSpec steady(LampColor color) {
        return new LampSpec(color, LampMode.STEADY);
    }

    public static LampSpec flashing(LampColor color) {
        return new LampSpec(color, LampMode.FLASHING);
    }

    public static LampSpec pulsed(LampColor color) {
        return new LampSpec(color, LampMode.PULSED);
    }
}
