package top.hibermod.ctdapi.signal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 信号系统的**开放注册表**：灯色、语义（Aspect）、机型（SignalType）。
 *
 * <p>注册时机：mod 构造期或 {@code FMLCommonSetupEvent}。宿主在
 * {@code FMLLoadCompleteEvent} 前后调用 {@link #freeze()}，此后禁止注册（早失败优于晚出错）。
 *
 * <p>这与 Create 的注册表形成对比：{@code Schedule.INSTRUCTION_TYPES} 是无冻结的裸 List、
 * {@code EdgePointType.TYPES} 是静默覆盖的 HashMap。信号规格一旦不一致，
 * 表现是「灯亮错了」这种极难排查的问题，所以这里**强制校验**：
 *
 * <ul>
 *   <li>id 重复 → 直接抛异常（不静默覆盖）</li>
 *   <li>机型引用了未注册的 Aspect → {@link #freeze()} 时报错并列出全部问题</li>
 *   <li>灯位越界 → 构造 {@link SignalType} 时就抛</li>
 * </ul>
 */
public final class SignalRegistries {

    private SignalRegistries() {}

    private static final Map<ResourceLocation, LampColor> LAMPS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Aspect> ASPECTS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, SignalType> TYPES = new LinkedHashMap<>();

    private static boolean frozen;
    private static boolean bootstrapped;

    // ── 注册 ──────────────────────────────────────────────────────────────

    public static LampColor registerLamp(LampColor color) {
        checkOpen();
        if (LAMPS.putIfAbsent(color.id(), color) != null) {
            throw new IllegalStateException("Duplicate lamp color id: " + color.id());
        }
        return color;
    }

    public static Aspect registerAspect(Aspect aspect) {
        checkOpen();
        if (ASPECTS.putIfAbsent(aspect.id(), aspect) != null) {
            throw new IllegalStateException("Duplicate aspect id: " + aspect.id());
        }
        return aspect;
    }

    public static SignalType registerType(SignalType type) {
        checkOpen();
        if (TYPES.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("Duplicate signal type id: " + type.id());
        }
        return type;
    }

    // ── 查询 ──────────────────────────────────────────────────────────────

    public static Optional<LampColor> lamp(ResourceLocation id) {
        return Optional.ofNullable(LAMPS.get(id));
    }

    public static Optional<Aspect> aspect(ResourceLocation id) {
        return Optional.ofNullable(ASPECTS.get(id));
    }

    public static Optional<SignalType> type(ResourceLocation id) {
        return Optional.ofNullable(TYPES.get(id));
    }

    /**
     * 取机型，找不到时回退到 {@link #fallbackType()}。
     *
     * <p>宿主在读取存档里的未知机型时用它，避免旧档案直接报废。
     */
    public static SignalType typeOrFallback(ResourceLocation id) {
        SignalType t = TYPES.get(id);
        return t != null ? t : fallbackType();
    }

    public static Collection<LampColor> lamps() {
        return LAMPS.values();
    }

    public static Collection<Aspect> aspects() {
        return ASPECTS.values();
    }

    public static Collection<SignalType> types() {
        return TYPES.values();
    }

    /** 未知机型时的回退（三显示区间通过信号机）。 */
    public static SignalType fallbackType() {
        return Builtin.BLOCK3;
    }

    // ── 生命周期 ──────────────────────────────────────────────────────────

    public static boolean frozen() {
        return frozen;
    }

    /**
     * 冻结注册表并做一致性校验。宿主在模组加载完成后调用。
     *
     * @throws IllegalStateException 校验失败时抛出，消息里列出全部问题
     */
    public static void freeze() {
        if (frozen) return;

        StringBuilder problems = new StringBuilder();
        for (SignalType type : TYPES.values()) {
            for (ResourceLocation aspectId : type.aspectMap().keySet()) {
                if (!ASPECTS.containsKey(aspectId)) {
                    problems.append("\n  - ").append(type.id()).append(" references unknown aspect ").append(aspectId);
                }
            }
            if (!type.aspectMap().containsKey(Builtin.STOP.id())) {
                problems.append("\n  - ").append(type.id()).append(" does not map the STOP aspect");
            }
        }
        if (problems.length() > 0) {
            throw new IllegalStateException("Signal registry validation failed:" + problems);
        }
        frozen = true;
    }

    /** 仅供测试使用：清空并允许重新注册。生产环境不要调用。 */
    public static void resetForTests() {
        LAMPS.clear();
        ASPECTS.clear();
        TYPES.clear();
        frozen = false;
        bootstrapped = false;
    }

    private static void checkOpen() {
        if (frozen) {
            throw new IllegalStateException("Signal registries are frozen; register during mod init instead");
        }
    }

    // ── 内置内容 ──────────────────────────────────────────────────────────

    /**
     * 注册内置的灯色、语义与几种常见机型。宿主应在 mod init 期调用一次。
     *
     * <p>内置内容只是**起点**——任何铁路制度都可以在此基础上注册自己的机型。
     */
    public static void bootstrap() {
        if (bootstrapped) return;
        bootstrapped = true;

        registerLamp(LampColor.RED);
        registerLamp(LampColor.YELLOW);
        registerLamp(LampColor.GREEN);
        registerLamp(LampColor.WHITE);
        registerLamp(LampColor.LUNAR_WHITE);
        registerLamp(LampColor.BLUE);
        registerLamp(LampColor.PURPLE);

        registerAspect(Builtin.STOP);
        registerAspect(Builtin.CAUTION);
        registerAspect(Builtin.ADVANCE_CAUTION);
        registerAspect(Builtin.PROCEED);
        registerAspect(Builtin.SHUNT);
        registerAspect(Builtin.CALL_ON);
        registerAspect(Builtin.DARK);

        registerType(Builtin.BLOCK3);
        registerType(Builtin.BLOCK4);
        registerType(Builtin.HOME4);
        registerType(Builtin.STARTING4);
        registerType(Builtin.SHUNT_2);
        registerType(Builtin.DISTANT);
        registerType(Builtin.ROUTE_INDICATOR);
    }

    /** 内置语义与机型的定义。 */
    public static final class Builtin {

        private Builtin() {}

        private static ResourceLocation id(String path) {
            return ResourceLocation.fromNamespaceAndPath("ctdapi", path);
        }

        // ── 语义 ──────────────────────────────────────────────────────────

        public static final Aspect STOP =
            new Aspect(id("stop"), Component.translatable("aspect.ctdapi.stop"), 0.00, Aspect.Category.STOP);

        public static final Aspect CAUTION =
            new Aspect(id("caution"), Component.translatable("aspect.ctdapi.caution"), 0.55, Aspect.Category.CAUTION);

        public static final Aspect ADVANCE_CAUTION =
            new Aspect(id("advance_caution"), Component.translatable("aspect.ctdapi.advance_caution"),
                0.40, Aspect.Category.ADVANCE_CAUTION);

        public static final Aspect PROCEED =
            new Aspect(id("proceed"), Component.translatable("aspect.ctdapi.proceed"), 1.00, Aspect.Category.PROCEED);

        public static final Aspect SHUNT =
            new Aspect(id("shunt"), Component.translatable("aspect.ctdapi.shunt"), 0.30, Aspect.Category.SHUNT);

        public static final Aspect CALL_ON =
            new Aspect(id("call_on"), Component.translatable("aspect.ctdapi.call_on"), 0.20, Aspect.Category.CALL_ON);

        public static final Aspect DARK =
            new Aspect(id("dark"), Component.translatable("aspect.ctdapi.dark"), 0.00, Aspect.Category.DARK);

        // ── 机型 ──────────────────────────────────────────────────────────

        /** 三显示区间通过信号机：红 / 黄 / 绿。 */
        public static final SignalType BLOCK3 = SignalType.builder(id("block_3"))
            .name(Component.translatable("signal_type.ctdapi.block_3"))
            .lamp(LampColor.RED).lamp(LampColor.YELLOW).lamp(LampColor.GREEN)
            .expresses(STOP.id(), 0)
            .expresses(CAUTION.id(), 1)
            .expresses(PROCEED.id(), 2)
            .semantics(SignalSemantics.BLOCK)
            .build();

        /** 四显示区间通过信号机：红 / 黄 / 黄 / 绿（双黄 = 预告两区间后关闭）。 */
        public static final SignalType BLOCK4 = SignalType.builder(id("block_4"))
            .name(Component.translatable("signal_type.ctdapi.block_4"))
            .lamp(LampColor.RED).lamp(LampColor.YELLOW).lamp(LampColor.YELLOW).lamp(LampColor.GREEN)
            .expresses(STOP.id(), 0)
            .expresses(CAUTION.id(), 1)
            .expresses(ADVANCE_CAUTION.id(), 1, 2)
            .expresses(PROCEED.id(), 3)
            .semantics(SignalSemantics.BLOCK)
            .build();

        /** 四显示进站信号机。 */
        public static final SignalType HOME4 = SignalType.builder(id("home_4"))
            .name(Component.translatable("signal_type.ctdapi.home_4"))
            .lamp(LampColor.RED).lamp(LampColor.YELLOW).lamp(LampColor.YELLOW).lamp(LampColor.GREEN)
            .expresses(STOP.id(), 0)
            .expresses(CAUTION.id(), 1)
            .expresses(ADVANCE_CAUTION.id(), 1, 2)
            .expresses(PROCEED.id(), 3)
            .expresses(CALL_ON.id(), LampDisplay.flashing(0))
            .semantics(SignalSemantics.HOME)
            .priority(10)
            .build();

        /** 四显示出站信号机。 */
        public static final SignalType STARTING4 = SignalType.builder(id("starting_4"))
            .name(Component.translatable("signal_type.ctdapi.starting_4"))
            .lamp(LampColor.RED).lamp(LampColor.YELLOW).lamp(LampColor.YELLOW).lamp(LampColor.GREEN)
            .expresses(STOP.id(), 0)
            .expresses(CAUTION.id(), 1)
            .expresses(ADVANCE_CAUTION.id(), 1, 2)
            .expresses(PROCEED.id(), 3)
            .semantics(SignalSemantics.STARTING)
            .priority(10)
            .build();

        /** 调车信号机：白（允许调车）/ 蓝（禁止调车）。 */
        public static final SignalType SHUNT_2 = SignalType.builder(id("shunt_2"))
            .name(Component.translatable("signal_type.ctdapi.shunt_2"))
            .lamp(LampColor.WHITE).lamp(LampColor.BLUE)
            .expresses(SHUNT.id(), 0)
            .expresses(STOP.id(), 1)
            .semantics(SignalSemantics.SHUNT)
            .build();

        /**
         * 预告 / 复示信号机：黄 / 绿。
         *
         * <p><b>不是停车点</b>——它只影响减速曲线。当成边界会让列车提前停死。
         */
        public static final SignalType DISTANT = SignalType.builder(id("distant"))
            .name(Component.translatable("signal_type.ctdapi.distant"))
            .lamp(LampColor.YELLOW).lamp(LampColor.GREEN)
            .expresses(CAUTION.id(), 0)
            .expresses(PROCEED.id(), 1)
            .semantics(SignalSemantics.DISTANT)
            .build();

        /** 进路表示器：单灯，只显示方向。 */
        public static final SignalType ROUTE_INDICATOR = SignalType.builder(id("route_indicator"))
            .name(Component.translatable("signal_type.ctdapi.route_indicator"))
            .lamp(LampColor.WHITE)
            .expresses(DARK.id())
            .expresses(PROCEED.id(), 0)
            .semantics(SignalSemantics.ROUTE_INDICATOR)
            .build();
    }
}
