package top.hibermod.ctdapi.signal;

/**
 * 一个灯座的点亮指令：第几号灯座、以什么方式亮。
 *
 * <p>机型的 {@link SignalType#aspectMap()} 用它的列表来描述「这个 Aspect 在本机型上怎么显示」。
 *
 * @param slot 灯座下标（对应 {@link SignalType#lampLayout()} 的位置）
 * @param mode 常亮 / 闪烁 / 脉冲
 */
public record LampDisplay(int slot, LampMode mode, @org.jetbrains.annotations.Nullable LampColor color) {

    public LampDisplay {
        if (slot < 0) throw new IllegalArgumentException("slot must be >= 0, got " + slot);
    }

    /** 用灯座自身的颜色（传统单色灯）。 */
    public static LampDisplay steady(int slot) {
        return new LampDisplay(slot, LampMode.STEADY, null);
    }

    /**
     * 指定颜色——双灯组合制式需要它。
     *
     * <p>真实的两灯位 CBTC 信号机，每个灯位都能显示红/黄/绿，
     * 「绿-绿」「绿-黄」「绿-红」这类组合才有意义。
     * 只按灯座固定颜色建模，是无法表达这些组合的。
     */
    public static LampDisplay steady(int slot, LampColor color) {
        return new LampDisplay(slot, LampMode.STEADY, color);
    }

    /** 实际显示的颜色：指定了就用指定的，否则用灯座的。 */
    public LampColor effectiveColor(LampColor slotDefault) {
        return color == null ? slotDefault : color;
    }

    public static LampDisplay flashing(int slot) {
        return new LampDisplay(slot, LampMode.FLASHING, null);
    }

    public static LampDisplay flashing(int slot, LampColor color) {
        return new LampDisplay(slot, LampMode.FLASHING, color);
    }
}
