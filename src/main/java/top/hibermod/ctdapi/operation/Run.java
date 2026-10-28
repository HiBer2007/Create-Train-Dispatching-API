package top.hibermod.ctdapi.operation;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/**
 * 车次：一次具体的运营任务。
 *
 * <p>与 {@link top.hibermod.ctdapi.timetable.Service} 的分工：
 * <ul>
 *   <li>{@code Service} 是**交路**——「京沪高铁上行」这种长期存在的东西，含停站表与速度等级；</li>
 *   <li>{@code Run} 是**车次**——「G1234，今天 08:00 从 A 站发车」这种一次性的东西。</li>
 * </ul>
 * 交路一年改一次，车次每天都不一样。合成一个概念的话，改一趟车就要动交路定义。
 *
 * @param serviceId        所属交路（{@code Service} 的 id）
 * @param runNumber        车次号，例如 {@code "G1234"}；可以不唯一（重联时分段）
 * @param plannedDeparture 计划发车时刻（游戏刻或外部纪元，由宿主约定）
 */
public record Run(ResourceLocation serviceId, String runNumber, long plannedDeparture) {

    public Run {
        Objects.requireNonNull(serviceId, "serviceId");
        Objects.requireNonNull(runNumber, "runNumber");
        if (runNumber.isBlank()) throw new IllegalArgumentException("run number must not be blank");
    }

    @Override
    public String toString() {
        return runNumber + "@" + serviceId;
    }
}
