package top.hibermod.ctdapi.signal;

import net.minecraft.resources.ResourceLocation;

/**
 * 灯的颜色。
 *
 * <p><b>不设枚举</b>——不同国家的信号色系差异很大（中国：红/黄/绿/月白；日本：还有紫色中继；
 * 德国：还有蓝色 Zs）。这里只描述「这是什么颜色」，具体哪个国家怎么用由 {@link SignalType} 决定。
 *
 * <p>内置色只是**常用值**，不是封闭集合。你可以 {@link SignalRegistries#registerLamp} 注册新色。
 *
 * @param id  稳定标识，**一旦发布不可改名**（存档与网络都用它）
 * @param rgb 灯亮起时的颜色（0xRRGGBB）。宿主默认渲染器用它上色，自定义渲染可忽略
 */
public record LampColor(ResourceLocation id, int rgb) {

    /** 红灯：禁止越过。 */
    public static final LampColor RED = new LampColor(key("red"), 0xFF3B30);

    /** 黄灯：注意。 */
    public static final LampColor YELLOW = new LampColor(key("yellow"), 0xFFB300);

    /** 绿灯：通行。 */
    public static final LampColor GREEN = new LampColor(key("green"), 0x34C759);

    /** 白灯：调车。 */
    public static final LampColor WHITE = new LampColor(key("white"), 0xF2F2F7);

    /** 月白（蓝白）：引导信号。 */
    public static final LampColor LUNAR_WHITE = new LampColor(key("lunar_white"), 0xCFE8FF);

    /** 蓝灯：禁止调车。 */
    public static final LampColor BLUE = new LampColor(key("blue"), 0x2E6BFF);

    /** 紫灯：中继信号（日本常用）。 */
    public static final LampColor PURPLE = new LampColor(key("purple"), 0xA24BFF);

    private static ResourceLocation key(String path) {
        return ResourceLocation.fromNamespaceAndPath("ctdapi", path);
    }

    @Override
    public String toString() {
        return id.toString();
    }
}
