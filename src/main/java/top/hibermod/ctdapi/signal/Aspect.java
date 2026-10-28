package top.hibermod.ctdapi.signal;

import java.util.Objects;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 一个**信号显示语义**（Aspect）：代表什么含义、允许多快。
 *
 * <p><b>刻意不包含灯位</b>。「通行」在四显示机上亮第 4 号灯座、在三显示机上亮第 3 号，
 * 但它们是**同一个语义**。灯位映射属于机型，见 {@link SignalType#aspectMap()}。
 *
 * <p>这样设计的好处：内核只说「把 X 号信号设为 {@code tcm:proceed}」，
 * 换成任何机型都能正确显示；第三方铁路制度也可以复用语义、只换灯位。
 *
 * @param id            稳定标识
 * @param displayName   显示名
 * @param speedFraction 允许的速度系数（0..1），相对列车 {@code maxSpeed}
 * @param category      类别，供内核快速判断（不必逐个比较 id）
 */
public record Aspect(
    ResourceLocation id,
    Component displayName,
    double speedFraction,
    Category category
) {

    public Aspect {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(category, "category");
        if (speedFraction < 0 || speedFraction > 1) {
            throw new IllegalArgumentException("speedFraction must be in [0,1], got " + speedFraction);
        }
    }

    /** 类别。内核可以只按类别行事，不必认识具体制度。 */
    public enum Category {
        /** 停车：禁止越过。 */
        STOP,
        /** 注意：前方有限制。 */
        CAUTION,
        /** 预告注意：前方两个区间后关闭（双黄）。 */
        ADVANCE_CAUTION,
        /** 通行：按允许速度。 */
        PROCEED,
        /** 调车：允许低速作业。 */
        SHUNT,
        /** 引导：闭塞失效时的低速授权。 */
        CALL_ON,
        /** 灭灯 / 无效。**不等于红灯**，宿主应视为「无此信号」。 */
        DARK
    }

    /** 是否禁止越过。 */
    public boolean isStop() {
        return category == Category.STOP;
    }

    /** 是否为「允许运行」。灭灯不算。 */
    public boolean grantsMovement() {
        return category != Category.STOP && category != Category.DARK;
    }

    /** 更严格的那一个（按限速比较），用于「原生 + 内核」两路来源合并。 */
    public Aspect restrictWith(Aspect other) {
        if (other == null) return this;
        return this.speedFraction <= other.speedFraction ? this : other;
    }
}
