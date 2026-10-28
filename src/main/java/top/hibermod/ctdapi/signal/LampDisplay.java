package top.hibermod.ctdapi.signal;

/**
 * 一个灯座的点亮指令：第几号灯座、以什么方式亮。
 *
 * <p>机型的 {@link SignalType#aspectMap()} 用它的列表来描述「这个 Aspect 在本机型上怎么显示」。
 *
 * @param slot 灯座下标（对应 {@link SignalType#lampLayout()} 的位置）
 * @param mode 常亮 / 闪烁 / 脉冲
 */
public record LampDisplay(int slot, LampMode mode) {

    public LampDisplay {
        if (slot < 0) throw new IllegalArgumentException("slot must be >= 0, got " + slot);
    }

    public static LampDisplay steady(int slot) {
        return new LampDisplay(slot, LampMode.STEADY);
    }

    public static LampDisplay flashing(int slot) {
        return new LampDisplay(slot, LampMode.FLASHING);
    }
}
