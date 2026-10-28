package top.hibermod.ctdapi.traction;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 牵引**标签**与**速度等级**。
 *
 * <h2>这里没有电气化模型——那属于联动方</h2>
 * 电力、受电弓、第三轨、流体燃料，都不是本 API 的模型，而是其他模组提供的。
 * 想知道「这车现在还有没有能」，请看 {@link top.hibermod.ctdapi.interop.Interop}，
 * 不要在这个包里加 {@code isElectrified} 之类的字段。
 *
 * <p>这个包只保留两样真正属于我们的东西：
 * <ul>
 *   <li>{@link TractionType} —— 一个**显示用的标签**（"电力动车组"/"内燃机车"），
 *       给 HUD、PIS、统计用。它不参与任何判定。</li>
 *   <li>{@link SpeedClass} —— **车次等级**，属于时刻表，是调度真正需要的约束。</li>
 * </ul>
 */
public final class Traction {

    private Traction() {}

    /**
     * 牵引方式标签。
     *
     * <p>只是名字。**不要**在这里加 {@code needsExternalSupply} 之类的布尔值——
     * 那会把「车要电」和「线有电」混成一件事，而后者只有联动方知道。
     */
    public record TractionType(ResourceLocation id, Component displayName) {
        public TractionType {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    /**
     * 速度等级（车次等级）。
     *
     * <p>例：`高速` / `快速` / `普快` / `调车`。交路声明里带一个等级，
     * ATO 据此限制目标速度——**这比让每个内核自己判断等级可靠**。
     *
     * @param maxSpeed 该等级的速度上限（格/tick，与 Create 内部单位一致）
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

    // ── 注册表 ────────────────────────────────────────────────────────────

    private static final Map<ResourceLocation, TractionType> TRACTIONS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, SpeedClass> SPEED_CLASSES = new LinkedHashMap<>();
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

    public static Optional<TractionType> type(ResourceLocation id) {
        return Optional.ofNullable(TRACTIONS.get(id));
    }

    public static Optional<SpeedClass> speedClass(ResourceLocation id) {
        return Optional.ofNullable(SPEED_CLASSES.get(id));
    }

    public static Collection<TractionType> types() {
        return TRACTIONS.values();
    }

    public static Collection<SpeedClass> speedClasses() {
        return SPEED_CLASSES.values();
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
        frozen = false;
    }

    private static void checkOpen() {
        if (frozen) {
            throw new IllegalStateException("Traction registries are frozen; register during mod init instead");
        }
    }

    /** 内置标签与速度等级。扩展方可以直接用，也可以注册自己的。 */
    public static final class Builtin {
        private Builtin() {}

        private static ResourceLocation id(String path) {
            return ResourceLocation.fromNamespaceAndPath("ctdapi", path);
        }

        // ── 牵引标签 ──────────────────────────────────────────────────────

        public static final TractionType ELECTRIC =
            new TractionType(id("electric"), Component.translatable("traction.ctdapi.electric"));

        public static final TractionType DIESEL =
            new TractionType(id("diesel"), Component.translatable("traction.ctdapi.diesel"));

        public static final TractionType STEAM =
            new TractionType(id("steam"), Component.translatable("traction.ctdapi.steam"));

        public static final TractionType HYBRID =
            new TractionType(id("hybrid"), Component.translatable("traction.ctdapi.hybrid"));

        // ── 速度等级 ──────────────────────────────────────────────────────

        public static final SpeedClass SHUNT =
            new SpeedClass(id("shunt"), Component.translatable("speed_class.ctdapi.shunt"), 0.40, 300);

        public static final SpeedClass FREIGHT =
            new SpeedClass(id("freight"), Component.translatable("speed_class.ctdapi.freight"), 0.80, 200);

        public static final SpeedClass PASSENGER =
            new SpeedClass(id("passenger"), Component.translatable("speed_class.ctdapi.passenger"), 1.20, 100);

        public static final SpeedClass HIGH_SPEED =
            new SpeedClass(id("high_speed"), Component.translatable("speed_class.ctdapi.high_speed"), 1.40, 50);

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
