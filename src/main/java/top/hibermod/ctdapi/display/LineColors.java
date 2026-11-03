package top.hibermod.ctdapi.display;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;

/**
 * 线路颜色注册表。
 *
 * <p>显示件（站台屏、发车板……）的顶部色条取这里的颜色。内核或资源包可以注册自己的色号，
 * 例如 {@code LineColors.register(ResourceLocation.parse("ctd:line11"), 0xFFC8D400)}。
 *
 * <p>不存 alpha 以外的语义：颜色就是 ARGB，显示件自己决定怎么用。
 */
public final class LineColors {

    private LineColors() {}

    private static final Map<ResourceLocation, Integer> COLORS = new LinkedHashMap<>();

    /** 注册（或覆盖）一个色号。 */
    public static void register(ResourceLocation id, int argb) {
        if (id == null) return;
        COLORS.put(id, argb);
    }

    /** 取全部已注册色号（保持注册顺序）。 */
    public static Map<ResourceLocation, Integer> all() {
        return Map.copyOf(COLORS);
    }

    /** 按 id 取色；没有就返回 fallback。 */
    public static int getOrDefault(ResourceLocation id, int fallback) {
        Integer v = COLORS.get(id);
        return v == null ? fallback : v;
    }

    public static int size() {
        return COLORS.size();
    }

    public static void clear() {
        COLORS.clear();
    }
}