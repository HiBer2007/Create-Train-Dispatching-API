package top.hibermod.ctdapi.device;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.Rail;

/**
 * 设备（地面 / 车载）的注册与实例模型。
 *
 * <p>信号机不走这里——它有自己的一整套语义（{@code signal} 包）。这里管的是**其余一切**：
 * 道岔转辙机、计轴、轨道电路、应答器、平交道、站台门、发车指示器、车载 ATP/ATO、电台……
 *
 * <h2>为什么用「类型 + 状态标签」而不是为每种设备定接口</h2>
 * 设备的形态差异太大：计轴器只需要上报「有一列车经过」，站台门需要接受开关命令，
 * 应答器只需要被读到。如果为每种设备定一个 Java 接口，这个包会无限膨胀。
 *
 * <p>所以这里只定**三个共同点**：身份、位置、可操作性。其余状态与命令用
 * {@link CompoundTag} 承载，schema 由各个 {@code DeviceType} 的实现自己定义——
 * 扩展方可以自由扩展，而 API 保持稳定。指令类型是 {@link top.hibermod.ctdapi.Command.DeviceCommand}。
 *
 * <h2>设备的两种归属</h2>
 * <ul>
 *   <li><b>地面设备</b>：固定在轨道上某一点，用 {@code edge + position} 定位；</li>
 *   <li><b>车载设备</b>：跟着列车走，用 {@code trainId} 定位，{@code edge/position} 为空。</li>
 * </ul>
 */
public final class Devices {

    private Devices() {}

    /** 设备大类。 */
    public enum Kind {
        /** 地面设备。 */
        GROUND,
        /** 车载设备。 */
        ONBOARD
    }

    /**
     * 一类设备。
     *
     * @param id            稳定标识，**一旦发布不可改名**
     * @param displayName   显示名
     * @param kind          地面 / 车载
     * @param capabilities  能力标签（自由定义，建议用短横线命名）。见 {@link Capabilities}
     * @param commandable   是否接受调度指令（否则只能只读上报）
     * @param signalSystems 车载设备能操作哪些信号制度。地面设备留空。
     *                      <b>一列车能支持几套信号系统，取决于它装了哪些车载设备。</b>
     */
    public record DeviceType(
        ResourceLocation id,
        Component displayName,
        Kind kind,
        Set<String> capabilities,
        boolean commandable,
        Set<ResourceLocation> signalSystems
    ) {
        public DeviceType {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(kind, "kind");
            capabilities = Set.copyOf(capabilities);
            signalSystems = signalSystems == null ? Set.of() : Set.copyOf(signalSystems);
        }

        /** 不带信号系统能力的便捷构造（多数地面设备用这个）。 */
        public DeviceType(ResourceLocation id, Component displayName, Kind kind,
                          Set<String> capabilities, boolean commandable) {
            this(id, displayName, kind, capabilities, commandable, Set.of());
        }

        public boolean has(String capability) {
            return capabilities.contains(capability);
        }

        /** 本设备能否操作某套信号制度。 */
        public boolean operates(ResourceLocation signalSystemId) {
            return signalSystems.contains(signalSystemId);
        }
    }

    /**
     * 一个设备实例（世界里真实存在的那一个）。
     *
     * @param id          实例 UUID，**必须持久且稳定**
     * @param typeId      所属类型
     * @param trainId     车载设备的宿主列车；地面设备为 {@code null}
     * @param edge        地面设备所在边；车载设备为 {@code null}
     * @param position    地面设备沿边的位置（格）；车载设备为 0
     * @param operational 是否在线可用（断电/损坏时为 false，调度应忽略它）
     * @param state       类型自定义的状态快照。只读，宿主每帧重建
     */
    public record DeviceSite(
        UUID id,
        ResourceLocation typeId,
        @Nullable UUID trainId,
        @Nullable Rail.EdgeId edge,
        double position,
        boolean operational,
        CompoundTag state
    ) {
        public DeviceSite {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(typeId, "typeId");
            Objects.requireNonNull(state, "state");
            if (edge == null && trainId == null) {
                throw new IllegalArgumentException(
                    "DeviceSite " + id + " must be either ground (edge) or onboard (trainId)");
            }
        }

        public boolean isOnboard() {
            return trainId != null;
        }

        /** 类型定义；未注册时为 {@code null}。 */
        @Nullable
        public DeviceType type() {
            return Devices.type(typeId).orElse(null);
        }

        /** 读一个状态字段；缺失时返回 {@code null}。 */
        @Nullable
        public CompoundTag field(String key) {
            return state.contains(key) ? state.getCompound(key) : null;
        }
    }

    /**
     * 建议的能力标签。不是强制枚举——扩展方可以定义自己的。
     */
    public static final class Capabilities {
        private Capabilities() {}

        // 地面
        public static final String SWITCH_MACHINE = "switch-machine";
        public static final String AXLE_COUNTER = "axle-counter";
        public static final String TRACK_CIRCUIT = "track-circuit";
        public static final String BALISE = "balise";
        public static final String LEVEL_CROSSING = "level-crossing";
        public static final String PLATFORM_DOOR = "platform-door";
        public static final String DEPARTURE_INDICATOR = "departure-indicator";
        public static final String TRAIN_DETECTOR = "train-detector";
        public static final String BUFFER_STOP = "buffer-stop";

        // 车载
        public static final String ATP = "atp";
        public static final String ATO = "ato";
        public static final String CAB_DISPLAY = "cab-display";
        public static final String RADIO = "radio";
        public static final String ODOMETER = "odometer";
        /** 信号系统车载设备：谁能「听懂」哪套信号制度（配 {@link DeviceType#signalSystems()}）。 */
        public static final String SIGNAL_EQUIPMENT = "signal-equipment";
        /** 受电弓。 */
        public static final String PANTOGRAPH = "pantograph";

        // 补给设施（地面）
        /** 燃料加注口。 */
        public static final String FUEL_PORT = "fuel-port";
        /** 充电桩 / 供电轨段。 */
        public static final String CHARGER = "charger";
        /** 上水口。 */
        public static final String WATER_PORT = "water-port";
    }

    // ── 注册表 ────────────────────────────────────────────────────────────

    private static final Map<ResourceLocation, DeviceType> TYPES = new LinkedHashMap<>();
    private static boolean frozen;

    public static DeviceType register(DeviceType type) {
        if (frozen) {
            throw new IllegalStateException("Device registry is frozen; register during mod init instead");
        }
        if (TYPES.putIfAbsent(type.id(), type) != null) {
            throw new IllegalStateException("Duplicate device type id: " + type.id());
        }
        return type;
    }

    public static Optional<DeviceType> type(ResourceLocation id) {
        return Optional.ofNullable(TYPES.get(id));
    }

    public static Collection<DeviceType> types() {
        return TYPES.values();
    }

    public static Collection<DeviceType> of(Kind kind) {
        return TYPES.values().stream().filter(t -> t.kind() == kind).toList();
    }

    /** 按能力标签过滤，便于「我需要一个能扳的道岔」这类查询。 */
    public static Collection<DeviceType> withCapability(String capability) {
        return TYPES.values().stream().filter(t -> t.has(capability)).toList();
    }

    public static List<DeviceType> ground() {
        return List.copyOf(of(Kind.GROUND));
    }

    public static List<DeviceType> onboard() {
        return List.copyOf(of(Kind.ONBOARD));
    }

    public static boolean frozen() {
        return frozen;
    }

    /** 冻结注册表。宿主在加载完成后调用。 */
    public static void freeze() {
        frozen = true;
    }

    /** 仅供测试使用。 */
    public static void resetForTests() {
        TYPES.clear();
        frozen = false;
    }
}
