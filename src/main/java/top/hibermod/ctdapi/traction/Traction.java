package top.hibermod.ctdapi.traction;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 牵引方式、速度等级、燃料与供电。
 *
 * <p>这一层解决的是「**车靠什么跑、能跑多快、还跑不跑得动**」——
 * 调度只关心距离授权，但 ATO 必须知道这些才能决定给多少牵引、什么时候该去补给。
 *
 * <h2>三个正交概念，不要混</h2>
 * <ul>
 *   <li>{@link TractionType} —— **怎么产生动力**（电力 / 内燃 / 蒸汽 / 混合）。开放注册。</li>
 *   <li>{@link SpeedClass} —— **这趟车允许跑多快**（车次等级）。属于交路，不属于车。</li>
 *   <li>{@link PowerSupply} —— **线路怎么供电**（接触网 / 第三轨 / 无）。属于线路，不属于车。</li>
 * </ul>
 *
 * <p>「电气化 vs 非电气化」不是一个是非题，而是「车要电力 / 线路有没有电」两个独立事实的**交集**。
 * 把二者合成一个布尔值，混合动力车和电气化区段的分界就没法表达。
 *
 * <h2>燃料与电量为什么要分开</h2>
 * 内燃车消耗燃料，电力车消耗电量（或直接取流），混合动力两者都有。
 * 用一个「能量」字段会在换挂、补燃料、受电弓升降时算错，所以分开记。
 */
public final class Traction {

    private Traction() {}

    /**
     * 一种牵引方式。
     *
     * @param id                   稳定标识
     * @param displayName          显示名
     * @param needsExternalSupply  是否**必须**从线路取流（纯电力）。为 true 时，无电区段无法牵引
     * @param needsFuel            是否**必须**自带燃料（内燃 / 蒸汽）
     * @param canUseExternalSupply 能否利用外部供电（可选的受电弓 / 第三轨集电靴）
     */
    public record TractionType(
        ResourceLocation id,
        Component displayName,
        boolean needsExternalSupply,
        boolean needsFuel,
        boolean canUseExternalSupply
    ) {
        public TractionType {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    /**
     * 速度等级（车次等级）。
     *
     * <p>例：`高速` 350 / `快速` 200 / `普快` 120 / `调车` 40（格/tick 口径见 {@link #maxSpeed()}）。
     * 交路声明里带一个等级，ATO 据此限制目标速度——**这比让每个内核自己判断等级可靠**。
     *
     * @param maxSpeed 该等级的速度上限（格/tick）
     * @param rank     数值越小越优先（用于冲突时的让行判断）
     */
    public record SpeedClass(
        ResourceLocation id,
        Component displayName,
        double maxSpeed,
        int rank
    ) {
        public SpeedClass {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            if (maxSpeed <= 0) {
                throw new IllegalArgumentException("SpeedClass " + id + " needs a positive maxSpeed");
            }
        }
    }

    /**
     * 一种燃料 / 储能介质。
     *
     * @param energyPerUnit 每单位可提供的能量当量（用于折算里程，单位自定义）
     */
    public record FuelType(
        ResourceLocation id,
        Component displayName,
        double energyPerUnit
    ) {
        public FuelType {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    /**
     * 一种供电制式（线路侧）。
     *
     * @param ratedSpeedHint 该制式下的推荐速度（格/tick），仅作提示
     */
    public record PowerSupply(
        ResourceLocation id,
        Component displayName,
        double ratedSpeedHint
    ) {
        public PowerSupply {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    /**
     * 一列车的**牵引能量状态**，由宿主每帧采样。
     *
     * @param tractionTypeId        牵引方式 id
     * @param externalSupplyLive    当前所在位置是否有可用供电（受电弓升起 / 集电靴接触 / 第三轨带电）
     * @param fuel                  当前燃料量
     * @param fuelCapacity          燃料容量（0 表示该车不带燃料）
     * @param fuelId                当前燃料种类；可为 {@code null}
     * @param energy                当前储能（电池 / 蓄电池组）
     * @param energyCapacity        储能容量（0 表示无储能）
     * @param pantographRaised      受电弓是否升起（非电力车恒为 false）
     */
    public record PowerState(
        ResourceLocation tractionTypeId,
        boolean externalSupplyLive,
        double fuel,
        double fuelCapacity,
        ResourceLocation fuelId,
        double energy,
        double energyCapacity,
        boolean pantographRaised
    ) {
        public PowerState {
            Objects.requireNonNull(tractionTypeId, "tractionTypeId");
        }

        /** 燃料余量比例，0..1；无燃料系统时为 1。 */
        public double fuelFraction() {
            return fuelCapacity <= 0 ? 1.0 : clamp01(fuel / fuelCapacity);
        }

        /** 储能余量比例，0..1；无储能系统时为 1。 */
        public double energyFraction() {
            return energyCapacity <= 0 ? 1.0 : clamp01(energy / energyCapacity);
        }

        /**
         * 现在**还能不能牵引**。
         *
         * <p>纯电力车需要外部供电或储能；内燃车需要燃料。
         * 混合动力只要两者之一即可。
         */
        public boolean canTraction() {
            TractionType t = Traction.type(tractionTypeId).orElse(null);
            if (t == null) return true;

            boolean external = externalSupplyLive && t.canUseExternalSupply();
            boolean stored = energyCapacity > 0 && energy > 0;
            boolean fuelOk = fuelCapacity <= 0 || fuel > 0;

            if (t.needsExternalSupply() && !t.needsFuel()) {
                return external || stored;
            }
            if (t.needsFuel() && !t.needsExternalSupply()) {
                return fuelOk;
            }
            // 混合动力
            return external || stored || fuelOk;
        }

        /** 是否需要尽快去补给（余量低于阈值，或已不可牵引）。 */
        public boolean needsRefill(double threshold) {
            return !canTraction() || fuelFraction() < threshold || energyFraction() < threshold;
        }

        /** 还能跑多远（格）的**粗估**，仅用于 ATO 提前规划补给。 */
        public double estimatedRange(double energyPerBlock) {
            if (energyPerBlock <= 0) return Double.POSITIVE_INFINITY;
            double usable = 0;
            if (fuelCapacity > 0) usable += fuel;
            if (energyCapacity > 0) usable += energy;
            return usable / energyPerBlock;
        }

        private static double clamp01(double v) {
            return v < 0 ? 0 : (v > 1 ? 1 : v);
        }
    }

    // ── 注册表 ────────────────────────────────────────────────────────────

    private static final Map<ResourceLocation, TractionType> TRACTIONS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, SpeedClass> SPEED_CLASSES = new LinkedHashMap<>();
    private static final Map<ResourceLocation, FuelType> FUELS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, PowerSupply> SUPPLIES = new LinkedHashMap<>();
    private static boolean frozen;

    public static TractionType registerTraction(TractionType type) {
        checkOpen();
        if (TRACTIONS.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("Duplicate traction type id: " + type.id());
        }
        return type;
    }

    public static SpeedClass registerSpeedClass(SpeedClass speedClass) {
        checkOpen();
        if (SPEED_CLASSES.putIfAbsent(speedClass.id(), speedClass) != null) {
            throw new IllegalStateException("Duplicate speed class id: " + speedClass.id());
        }
        return speedClass;
    }

    public static FuelType registerFuel(FuelType fuel) {
        checkOpen();
        if (FUELS.putIfAbsent(fuel.id(), fuel) != null) {
            throw new IllegalStateException("Duplicate fuel type id: " + fuel.id());
        }
        return fuel;
    }

    public static PowerSupply registerPowerSupply(PowerSupply supply) {
        checkOpen();
        if (SUPPLIES.putIfAbsent(supply.id(), supply) != null) {
            throw new IllegalStateException("Duplicate power supply id: " + supply.id());
        }
        return supply;
    }

    public static Optional<TractionType> type(ResourceLocation id) {
        return Optional.ofNullable(TRACTIONS.get(id));
    }

    public static Optional<SpeedClass> speedClass(ResourceLocation id) {
        return Optional.ofNullable(SPEED_CLASSES.get(id));
    }

    public static Optional<FuelType> fuel(ResourceLocation id) {
        return Optional.ofNullable(FUELS.get(id));
    }

    public static Optional<PowerSupply> supply(ResourceLocation id) {
        return Optional.ofNullable(SUPPLIES.get(id));
    }

    public static Collection<TractionType> types() {
        return TRACTIONS.values();
    }

    public static Collection<SpeedClass> speedClasses() {
        return SPEED_CLASSES.values();
    }

    public static Collection<FuelType> fuels() {
        return FUELS.values();
    }

    public static Collection<PowerSupply> supplies() {
        return SUPPLIES.values();
    }

    public static boolean frozen() {
        return frozen;
    }

    public static void freeze() {
        frozen = true;
    }

    public static void resetForTests() {
        TRACTIONS.clear();
        SPEED_CLASSES.clear();
        FUELS.clear();
        SUPPLIES.clear();
        frozen = false;
    }

    private static void checkOpen() {
        if (frozen) {
            throw new IllegalStateException("Traction registries are frozen; register during mod init instead");
        }
    }

    /** 常用牵引方式。扩展方可以直接用，也可以注册自己的。 */
    public static final class Builtin {
        private Builtin() {}

        private static ResourceLocation id(String path) {
            return ResourceLocation.fromNamespaceAndPath("ctdapi", path);
        }

        public static final TractionType ELECTRIC = new TractionType(
            id("electric"), Component.translatable("traction.ctdapi.electric"), true, false, true);

        public static final TractionType DIESEL = new TractionType(
            id("diesel"), Component.translatable("traction.ctdapi.diesel"), false, true, false);

        public static final TractionType STEAM = new TractionType(
            id("steam"), Component.translatable("traction.ctdapi.steam"), false, true, false);

        public static final TractionType HYBRID = new TractionType(
            id("hybrid"), Component.translatable("traction.ctdapi.hybrid"), false, true, true);

        public static final SpeedClass SHUNT = new SpeedClass(
            id("shunt"), Component.translatable("speed_class.ctdapi.shunt"), 0.40, 300);

        public static final SpeedClass FREIGHT = new SpeedClass(
            id("freight"), Component.translatable("speed_class.ctdapi.freight"), 0.80, 200);

        public static final SpeedClass PASSENGER = new SpeedClass(
            id("passenger"), Component.translatable("speed_class.ctdapi.passenger"), 1.20, 100);

        public static final SpeedClass HIGH_SPEED = new SpeedClass(
            id("high_speed"), Component.translatable("speed_class.ctdapi.high_speed"), 1.40, 50);

        /** 内置内容一次性注册。 */
        public static void bootstrap() {
            registerTraction(ELECTRIC);
            registerTraction(DIESEL);
            registerTraction(STEAM);
            registerTraction(HYBRID);
            registerSpeedClass(SHUNT);
            registerSpeedClass(FREIGHT);
            registerSpeedClass(PASSENGER);
            registerSpeedClass(HIGH_SPEED);
        }
    }
}
