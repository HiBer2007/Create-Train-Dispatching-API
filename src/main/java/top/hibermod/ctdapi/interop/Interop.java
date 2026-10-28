package top.hibermod.ctdapi.interop;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;

/**
 * 联动注册表：能源提供方 + 已知联动目标清单。
 *
 * <p>两类东西分开记：
 * <ul>
 *   <li>{@link EnergyProvider} —— **行为**：能回答"这车还有没有能"；</li>
 *   <li>{@link Target} —— **档案**：这个模组提供了什么能力（诊断与文档用，不参与决策）。</li>
 * </ul>
 * 分开的理由：没有联动模组时也得能启动，档案缺失不影响运行；
 * 而一个半成品适配器提供了错误的 {@code sample()} 会直接把列车停在正线上，
 * 所以行为必须单独、显式、可禁用。
 */
public final class Interop {

    private Interop() {}

    // ── 已知联动目标 ──────────────────────────────────────────────────────

    /** 联动能力分类。 */
    public enum Capability {
        /** 真实电力 / 受电弓 / 第三轨。 */
        POWER,
        /** 纯装饰的接触网与受电弓。 */
        PANTOGRAPH_DECOR,
        /** 流体燃料（汽鸣铁道的油罐车就是这一类）。 */
        FLUID_FUEL,
        /** 燃料物品。 */
        SOLID_FUEL,
        /** 充电 / 储能。 */
        ENERGY_STORAGE
    }

    /**
     * 一条联动档案。
     *
     * @param modId      对方模组 id
     * @param name       显示名
     * @param capability 提供的能力
     * @param note       说明（装在哪个模组、怎么对接）
     * @param providerId 对应我们这边登记的 {@link EnergyProvider#id()}；无适配器时为 {@code null}
     */
    public record Target(
        String modId,
        String name,
        Capability capability,
        String note,
        @Nullable ResourceLocation providerId
    ) {
        public Target {
            if (modId == null || modId.isBlank()) {
                throw new IllegalArgumentException("Target needs a modId");
            }
        }
    }

    private static final Map<String, Target> TARGETS = new LinkedHashMap<>();

    /** 登记一条联动档案（不产生行为）。 */
    public static Target describe(Target target) {
        TARGETS.put(target.modId() + "/" + target.capability(), target);
        return target;
    }

    public static Collection<Target> targets() {
        return TARGETS.values();
    }

    /** 某个模组是否在场。需要宿主注入探针，API 不直接依赖加载器。 */
    public static boolean isLoaded(String modId) {
        Predicate<String> probe = modProbe;
        return probe != null && probe.test(modId);
    }

    private static Predicate<String> modProbe;

    /**
     * 宿主注入「模组是否加载」的探针（通常是 {@code ModList.get()::isLoaded}）。
     *
     * <p>API 不直接 import 加载器类，这样离线自检与单元测试也能跑。
     */
    public static void setModProbe(Predicate<String> probe) {
        modProbe = probe;
    }

    // ── 能源提供方 ────────────────────────────────────────────────────────

    private static final Map<ResourceLocation, EnergyProvider> PROVIDERS = new LinkedHashMap<>();
    private static boolean frozen;

    public static EnergyProvider registerEnergyProvider(EnergyProvider provider) {
        if (frozen) {
            throw new IllegalStateException("Interop registry is frozen; register during mod init instead");
        }
        if (PROVIDERS.putIfAbsent(provider.id(), provider) != null) {
            throw new IllegalStateException("Duplicate energy provider id: " + provider.id());
        }
        return provider;
    }

    public static Optional<EnergyProvider> energyProvider(ResourceLocation id) {
        return Optional.ofNullable(PROVIDERS.get(id));
    }

    public static Collection<EnergyProvider> energyProviders() {
        return PROVIDERS.values();
    }

    /**
     * 聚合某列车的能源状态。
     *
     * <p>多个提供方（混合动力：电力 + 流体燃料）各自给出采样，按
     * {@link EnergyProvider.EnergySample#merge} 合并：状态取最坏、余量取最小。
     *
     * <p>**没有任何提供方时返回 {@link EnergyProvider.EnergySample#unknown()}**——
     * 也就是「不知道」，而不是「没电」。这一点必须守住：否则没装联动模组的存档
     * 里所有列车都会瞬间趴窝。
     */
    public static EnergyProvider.EnergySample sampleEnergy(top.hibermod.ctdapi.Telemetry.TrainState train) {
        EnergyProvider.EnergySample merged = null;
        for (EnergyProvider p : PROVIDERS.values()) {
            EnergyProvider.EnergySample s;
            try {
                s = p.sample(train);
            } catch (Throwable t) {
                // 一个适配器炸了不能拖垮全部列车
                continue;
            }
            if (s == null || !s.known()) continue;
            merged = merged == null ? s : merged.merge(s);
        }
        return merged == null ? EnergyProvider.EnergySample.unknown() : merged;
    }

    /** 把所有提供方的采样原样列出（诊断用，不做合并）。 */
    public static Map<ResourceLocation, EnergyProvider.EnergySample> sampleAll(
        top.hibermod.ctdapi.Telemetry.TrainState train) {
        Map<ResourceLocation, EnergyProvider.EnergySample> out = new LinkedHashMap<>();
        for (EnergyProvider p : PROVIDERS.values()) {
            try {
                EnergyProvider.EnergySample s = p.sample(train);
                if (s != null) out.put(p.id(), s);
            } catch (Throwable ignored) {
                // 诊断路径同样不该抛
            }
        }
        return out;
    }

    public static boolean frozen() {
        return frozen;
    }

    public static void freeze() {
        frozen = true;
    }

    public static void resetForTests() {
        PROVIDERS.clear();
        TARGETS.clear();
        frozen = false;
        modProbe = null;
    }

    // ── 内置档案 ──────────────────────────────────────────────────────────

    /**
     * 内置的联动档案：已核对过源码的四个能源 / 接触网来源。
     *
     * <p>这些**只是说明**，不会自动注册适配器——适配器由宿主或联动方自己提供。
     * 列在这里是为了 {@link top.hibermod.ctdapi.ApiCatalog} 能打印出一份「应该装什么」的清单。
     *
     * <p>⚠ 核对过源码的事实（分支与版本见各条注释）：
     * <ul>
     *   <li><b>Create: Electro Energetics</b>（`1.21.1`，`1.21.1-1.2.0`）—— 真正的**电气系统**：
     *       电线、连接器、蓄电池、变压器、电压仿真，以及**列车受电弓**。
     *       它 mixin 了 {@code Train}，通过 {@code ICEETrainExtension#getElectricTrainData()}
     *       暴露 {@code ElectricTrainData}，里面有 {@code lastVoltage}、{@code accumulatorCharge}、
     *       {@code pantographs}。**这是目前唯一能给出真实牵引电压的模组。**</li>
     *   <li><b>Create: Diesel Generators</b>（`1.21.1`）—— 提供**流体燃料**
     *       （柴油 / 生物柴油 / 植物油，{@code CDGTags} 里是 {@code TagKey<Fluid>}）
     *       与**旋转应力**（{@code ModularDieselEngineBlockEntity extends GeneratingKineticBlockEntity}）。
     *       它**没有** {@code IEnergyStorage}、没有受电弓、没有接触网。</li>
     *   <li><b>Create: Pantographs &amp; Wires</b>（`mc/1.21.1`）—— 纯装饰接触网与受电弓。</li>
     *   <li><b>Steam 'n' Rails</b> —— 流体燃料（油罐车）。</li>
     * </ul>
     */
    public static final class Known {
        private Known() {}

        public static final String ELECTRO_ENERGETICS = "electroenergetics";
        public static final String DIESEL_GENERATORS = "createdieselgenerators";
        public static final String PANTOGRAPHS_AND_WIRES = "pantographsandwires";
        public static final String RAILWAYS = "railways";

        /** Electro Energetics 的电压门限（默认值，来自其 {@code CVoltages}）。 */
        public static final double EE_TRAIN_MIN_VOLTAGE = 1900.0;
        public static final double EE_TRAIN_MAX_VOLTAGE = 5000.0;

        public static void bootstrap() {
            describe(new Target(
                ELECTRO_ENERGETICS,
                "Create: Electro Energetics",
                Capability.POWER,
                "真实电气系统 + 列车受电弓。经 ICEETrainExtension#getElectricTrainData() 读 lastVoltage / "
                    + "accumulatorCharge；电压低于 trainMinVoltage(默认 1900V) 即无牵引。",
                null));

            describe(new Target(
                ELECTRO_ENERGETICS,
                "Create: Electro Energetics",
                Capability.ENERGY_STORAGE,
                "车载蓄电池（accumulators / accumulatorCharge，单位库仑）。",
                null));

            describe(new Target(
                DIESEL_GENERATORS,
                "Create: Diesel Generators",
                Capability.FLUID_FUEL,
                "流体燃料（柴油/生物柴油/植物油）。燃料量以流体 id 为键放进 gauges。",
                null));

            describe(new Target(
                PANTOGRAPHS_AND_WIRES,
                "Create: Pantographs & Wires",
                Capability.PANTOGRAPH_DECOR,
                "纯装饰接触网。**不提供能源状态**，不要拿它当电力来源。",
                null));

            describe(new Target(
                RAILWAYS,
                "Steam 'n' Rails",
                Capability.FLUID_FUEL,
                "流体燃料（油罐车）。",
                null));
        }
    }
}
