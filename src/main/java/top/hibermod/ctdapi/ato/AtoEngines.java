package top.hibermod.ctdapi.ato;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.signal.SignalRegistries;

/**
 * ATO 引擎的注册表。
 *
 * <p>与信号制度的配对关系在 {@link #freeze()} 时校验：**每个 ATO 都必须指向一个已注册的信号制度**。
 * ATO 不能独立存在——没有信号制度就没有授权，没有授权就没有自动驾驶。
 *
 * <pre>
 * AtoEngines.register(new MyAto());          // MyAto.signalSystemId() = ctdapi:mainline_4
 * AtoEngines.hasFor(MAINLINE_4);             // true
 * AtoEngines.hasFor(YARD);                   // false —— 调车场没写 ATO，不能启动自动驾驶
 * </pre>
 */
public final class AtoEngines {

    private AtoEngines() {}

    private static final Map<ResourceLocation, AtoEngine> ENGINES = new LinkedHashMap<>();
    private static boolean frozen;

    public static AtoEngine register(AtoEngine engine) {
        if (frozen) {
            throw new IllegalStateException("ATO registry is frozen; register during mod init instead");
        }
        if (ENGINES.putIfAbsent(engine.id(), engine) != null) {
            throw new IllegalStateException("Duplicate ATO id: " + engine.id());
        }
        return engine;
    }

    public static Optional<AtoEngine> get(ResourceLocation id) {
        return Optional.ofNullable(ENGINES.get(id));
    }

    /**
     * 替换一个已注册的 ATO（**热重载专用**）。
     *
     * <p>重复 id 不抛异常，而是换掉实例。正常注册仍走 {@link #register}——
     * 别让热重载的方便把「重复注册」这个早期错误吞掉。
     *
     * @return 是否存在旧的注册
     */
    public static boolean replace(AtoEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException("engine must be non-null");
        }
        return ENGINES.put(engine.id(), engine) != null;
    }

    /**
     * 注销一个 ATO（**热重载专用**）。
     *
     * @return 是否存在过
     */
    public static boolean remove(ResourceLocation id) {
        return ENGINES.remove(id) != null;
    }

    /**
     * 某套信号制度对应的 ATO。
     *
     * <p>一套制度**最多一个** ATO；注册第二个会在 {@link #freeze()} 时报错。
     */
    public static Optional<AtoEngine> forSignalSystem(ResourceLocation signalSystemId) {
        for (AtoEngine e : ENGINES.values()) {
            if (e.signalSystemId().equals(signalSystemId)) {
                return Optional.of(e);
            }
        }
        return Optional.empty();
    }

    /** 该信号制度是否提供自动驾驶。 */
    public static boolean hasFor(ResourceLocation signalSystemId) {
        return forSignalSystem(signalSystemId).isPresent();
    }

    public static Collection<AtoEngine> all() {
        return ENGINES.values();
    }

    /**
     * 判定某列车在某套信号制度下能否启动自动驾驶。
     *
     * <p>两个条件缺一不可：
     * <ol>
     *   <li>该制度**提供了** ATO——没有 ATO 的信号引擎只能给建议速度；</li>
     *   <li>该车**装备了**对应信号系统的车载设备（{@link top.hibermod.ctdapi.device.Devices.DeviceType#signalSystems()}）。</li>
     * </ol>
     *
     * @param signalSystemId     进路所属交路使用的信号制度
     * @param trainSignalSystems 该车车载设备支持的信号制度集合
     */
    public static AtoEngine.Availability check(ResourceLocation signalSystemId,
                                               Collection<ResourceLocation> trainSignalSystems) {
        AtoEngine engine = forSignalSystem(signalSystemId).orElse(null);
        if (engine == null) {
            return AtoEngine.Availability.no(
                "signal system " + signalSystemId + " provides no ATO; autopilot unavailable");
        }
        if (trainSignalSystems == null || !trainSignalSystems.contains(signalSystemId)) {
            return AtoEngine.Availability.no(
                "train is not equipped for signal system " + signalSystemId);
        }
        return AtoEngine.Availability.ok();
    }

    public static boolean frozen() {
        return frozen;
    }

    /**
     * 冻结并校验。
     *
     * @throws IllegalStateException 某个 ATO 指向未注册的信号制度，或该制度下有多个 ATO
     */
    public static void freeze() {
        if (frozen) return;

        StringBuilder problems = new StringBuilder();
        Map<ResourceLocation, ResourceLocation> claimed = new LinkedHashMap<>();

        for (AtoEngine e : ENGINES.values()) {
            ResourceLocation sys = e.signalSystemId();
            if (sys == null) {
                problems.append("\n  - ATO ").append(e.id()).append(" declares no signalSystemId");
                continue;
            }
            if (SignalRegistries.system(sys).isEmpty()) {
                problems.append("\n  - ATO ").append(e.id())
                    .append(" targets unknown signal system ").append(sys);
            }
            ResourceLocation previous = claimed.putIfAbsent(sys, e.id());
            if (previous != null) {
                problems.append("\n  - signal system ").append(sys)
                    .append(" has two ATOs: ").append(previous).append(" and ").append(e.id());
            }
        }

        if (problems.length() > 0) {
            throw new IllegalStateException("ATO registry validation failed:" + problems);
        }
        frozen = true;
    }

    public static void resetForTests() {
        ENGINES.clear();
        frozen = false;
    }
}
