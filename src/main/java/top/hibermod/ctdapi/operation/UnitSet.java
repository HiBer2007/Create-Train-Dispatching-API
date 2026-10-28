package top.hibermod.ctdapi.operation;

import java.util.List;
import java.util.Objects;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 车组：若干车辆编号的固定编组。
 *
 * <p><b>为什么需要这一层</b>：重联、固定编组、动车组都是「多辆车一起跑一个车次」。
 * 如果没有车组，调度算法每次都得自己拼列表，而且拼接规则会散落在各个内核里。
 *
 * <p>单个车组可以只含一辆车——不要为此再造一个「单车 vs 车组」的分支，
 * 那是最容易长成两套代码的地方。
 *
 * @param id          车组标识（稳定，可进存档）
 * @param displayName 显示名（进 HUD / PIS）
 * @param units       成员车辆编号，**有序**：首位是车次的前端
 */
public record UnitSet(ResourceLocation id, Component displayName, List<UnitNumber> units) {

    public UnitSet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        units = List.copyOf(units);
        if (units.isEmpty()) {
            throw new IllegalArgumentException("unit set " + id + " must contain at least one unit");
        }
    }

    /** 单车车组。 */
    public static UnitSet of(ResourceLocation id, Component name, UnitNumber unit) {
        return new UnitSet(id, name, List.of(unit));
    }

    /** 该车组是否有 {@code n} 辆车。 */
    public boolean isLength(int n) {
        return units.size() == n;
    }

    public boolean contains(UnitNumber unit) {
        return units.contains(unit);
    }

    /** 车组总长需要宿主提供每辆车的长度；这里只做编号层面的合并。 */
    public UnitSet merge(UnitSet other) {
        List<UnitNumber> all = new java.util.ArrayList<>(units);
        for (UnitNumber u : other.units) {
            if (!all.contains(u)) all.add(u);
        }
        return new UnitSet(id, displayName, all);
    }
}
