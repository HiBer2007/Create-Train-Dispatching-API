package top.hibermod.ctdapi.interop;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.Telemetry;

/**
 * **能源联动**：电力、燃料、受电弓这些**不是本 API 的模型**，而属于别的模组。
 *
 * <p>本 API 只定义「怎么问、怎么答、怎么下发指令」这一层。谁真正发电、谁是第三轨、
 * 燃料是流体还是物品，全部由联动方决定。
 *
 * <h2>为什么不在 API 里建模电气化</h2>
 * 因为电气化的实现方式千差万别：
 * <ul>
 *   <li>有的模组用真实的电网与受电弓接触判定（{@code Create-Diesel-Generators}）；</li>
 *   <li>有的模组只是纯装饰的接触网（{@code Create-Pantographs-and-Wires}）；</li>
 *   <li>汽鸣铁道的燃料是**流体**，走流体罐而不是物品槽。</li>
 * </ul>
 * 在 API 里定义「供电制式」「燃料种类」这类枚举，等于要求所有这些模组按我们的想象来长——
 * 这是错的。API 只应声明：**我需要知道"现在还能不能给牵引"，你能给我一个数**。
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>没有装任何能源模组时，{@link EnergySample#unknown()} —— 视为可牵引，不干预；</li>
 *   <li>装了联动模组时，`sample()` 必须给出明确状态，不允许瞎猜；</li>
 *   <li>{@code gauges} 是**开放键值**：燃料量、电压、蒸汽压力、电池 SOC 随便放，
 *       键用 {@link ResourceLocation}（例如流体 id），显示交给 HUD/PIS。</li>
 * </ul>
 */
public interface EnergyProvider {

    /** 稳定标识。 */
    ResourceLocation id();

    /** 提供方模组 id（"createdieselgenerators" / "createpantographsandwires" / "railways"…）。 */
    String modId();

    /**
     * 采样某列车此刻的能源状态。
     *
     * <p><b>不要返回 {@code null} 表示"没电"</b>——那表示"不适用／不知道"，用
     * {@link EnergySample.Status#UNKNOWN}。这条区别是排障的关键：没电要停车，
     * 不知道不能停车。
     */
    @Nullable
    EnergySample sample(Telemetry.TrainState train);

    /**
     * 执行一条能源指令（升弓、切换供电、加注……）。
     *
     * <p>{@code payload} 的结构由提供方自己定——API 不解释它。
     * 默认实现忽略，表示该提供方只读。
     */
    default void apply(top.hibermod.ctdapi.Command.EnergyCommand command) {}

    /**
     * 一次能源采样。
     *
     * @param status  状态
     * @param fraction 0..1 的粗余量（未知时给 1.0）。ATO 用它判断「要不要提前补给」
     * @param gauges  开放键值：任意指标，键建议用流体/物品 id
     * @param summary 人读摘要（进 HUD），可为 {@code null}
     */
    record EnergySample(
        Status status,
        double fraction,
        Map<ResourceLocation, Double> gauges,
        @Nullable Component summary
    ) {
        public EnergySample {
            status = status == null ? Status.UNKNOWN : status;
            fraction = fraction < 0 ? 0 : (fraction > 1 ? 1 : fraction);
            gauges = gauges == null ? Map.of() : Map.copyOf(gauges);
        }

        /** 能源状态的四种情形。 */
        public enum Status {
            /** 不知道 / 没有联动模组。**不要因此停车**。 */
            UNKNOWN,
            /** 有能源。 */
            AVAILABLE,
            /** 还能跑但偏低。 */
            LOW,
            /** 耗尽。牵引无效。 */
            DEPLETED,
            /** 该列车不使用这种能源（例如电力车遇到燃料提供方）。 */
            NOT_APPLICABLE
        }

        /** 现在能不能给牵引。 */
        public boolean canTraction() {
            return status != Status.DEPLETED;
        }

        /** 是否需要补给。 */
        public boolean needsRefill() {
            return status == Status.LOW || status == Status.DEPLETED;
        }

        /** 联动方是否真的知道状态。 */
        public boolean known() {
            return status != Status.UNKNOWN && status != Status.NOT_APPLICABLE;
        }

        public static EnergySample unknown() {
            return new EnergySample(Status.UNKNOWN, 1.0, Map.of(), null);
        }

        public static EnergySample available(double fraction) {
            return new EnergySample(Status.AVAILABLE, fraction, Map.of(), null);
        }

        public static EnergySample low(double fraction, Component summary) {
            return new EnergySample(Status.LOW, fraction, Map.of(), summary);
        }

        public static EnergySample depleted(Component summary) {
            return new EnergySample(Status.DEPLETED, 0, Map.of(), summary);
        }

        public static EnergySample notApplicable() {
            return new EnergySample(Status.NOT_APPLICABLE, 1.0, Map.of(), null);
        }

        /**
         * 合并两个来源的采样（混合动力会同时得到电力和燃料两份）。
         *
         * <p>规则：状态取**最坏**，余量取**最小**，指标取并集。
         * 这样"电还有一半但油见底"会正确判成 {@link Status#LOW}。
         */
        public EnergySample merge(EnergySample other) {
            if (other == null || !other.known()) return this;
            if (!known()) return other;

            Status worst = worst(status, other.status);
            double min = Math.min(fraction, other.fraction);
            Map<ResourceLocation, Double> merged = new java.util.LinkedHashMap<>(gauges);
            merged.putAll(other.gauges);

            Component detail = summary != null ? summary : other.summary;
            return new EnergySample(worst, min, merged, detail);
        }

        private static Status worst(Status a, Status b) {
            List<Status> order = List.of(
                Status.UNKNOWN, Status.NOT_APPLICABLE, Status.AVAILABLE, Status.LOW, Status.DEPLETED);
            return order.indexOf(a) >= order.indexOf(b) ? a : b;
        }
    }
}
