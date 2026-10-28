package top.hibermod.ctdapi.timetable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * **交路（Service）** 的声明：一条线路上的一个运营模式，绑定一套信号制度。
 *
 * <p>这是「具体采用什么信号系统由时刻表自己决定」的落点。
 *
 * <p>为什么需要它：同一张轨道网上，干线可能用四显示自动闭塞，站场调车用白蓝调车信号，
 * 支线可能是单一红灯的简易闭塞。**决定用哪一套的不是玩家全局开关，而是这条交路的运营规则。**
 * 所以交路声明里必须带 {@link #signalSystemId()}，调度引擎按交路选系统、按系统发显示。
 *
 * <h2>谁注册</h2>
 * 由**时刻表扩展方**注册（它知道自己的线路与系统选择）。本 API 不定义时刻表的结构——
 * 时刻表怎么存、怎么算，完全属于扩展方；这里只固定「对外可见的那一层」：
 * 交路的标识、显示名、绑定哪套信号制度。
 *
 * <h2>与调度引擎的关系</h2>
 * 引擎可以完全不用这张表（把交路信息放在自己的存档里）。但注册了之后：
 * <ul>
 *   <li>宿主可以做一致性校验（交路引用的系统是否已注册、是否有该系统的机型）；</li>
 *   <li>PIS / HUD 可以用 {@link #id()} 做稳定的引用；</li>
 *   <li>调试时 {@link top.hibermod.ctdapi.ApiCatalog} 能列出「这台服务器上有哪些交路、各用什么系统」。</li>
 * </ul>
 *
 * @param id             稳定标识，**一旦发布不可改名**
 * @param code           对外车次号前缀（"G" / "S3" / "YARD"），PIS 用
 * @param line           线路名（可为 {@code null}，调车作业通常没有线路）
 * @param signalSystemId 本交路使用的信号制度（必须是已注册的 {@code SignalSystem}）
 * @param priority       调度优先级提示，数值越小越优先
 */
public record Service(
    ResourceLocation id,
    Component code,
    @Nullable Component line,
    ResourceLocation signalSystemId,
    int priority
) {

    public Service {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(signalSystemId, "signalSystemId");
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    /** 链式构造器。 */
    public static final class Builder {
        private final ResourceLocation id;
        private Component code;
        private Component line;
        private ResourceLocation signalSystemId;
        private int priority = 100;

        private Builder(ResourceLocation id) {
            this.id = id;
        }

        public Builder code(String code) {
            this.code = Component.literal(code);
            return this;
        }

        public Builder code(Component code) {
            this.code = code;
            return this;
        }

        public Builder line(Component line) {
            this.line = line;
            return this;
        }

        public Builder signalSystem(ResourceLocation systemId) {
            this.signalSystemId = systemId;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Service build() {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(signalSystemId, "signalSystemId");
            return new Service(id, code, line, signalSystemId, priority);
        }
    }

    // ── 注册表 ────────────────────────────────────────────────────────────

    private static final Map<ResourceLocation, Service> SERVICES = new LinkedHashMap<>();
    private static boolean frozen;

    public static Service register(Service service) {
        if (frozen) {
            throw new IllegalStateException("Service registry is frozen; register during mod init instead");
        }
        if (SERVICES.putIfAbsent(service.id(), service) != null) {
            throw new IllegalStateException("Duplicate service id: " + service.id());
        }
        return service;
    }

    public static Optional<Service> service(ResourceLocation id) {
        return Optional.ofNullable(SERVICES.get(id));
    }

    public static Collection<Service> services() {
        return SERVICES.values();
    }

    /** 使用了指定信号制度的全部交路。 */
    public static List<Service> usingSystem(ResourceLocation signalSystemId) {
        return SERVICES.values().stream()
            .filter(s -> s.signalSystemId().equals(signalSystemId))
            .toList();
    }

    public static boolean frozen() {
        return frozen;
    }

    /**
     * 冻结并校验：每条交路引用的信号制度必须已注册。
     *
     * <p>宿主应在 {@link top.hibermod.ctdapi.ApiCatalog#freezeAll()} 之前注册完交路。
     */
    public static void freeze() {
        StringBuilder problems = new StringBuilder();
        for (Service s : SERVICES.values()) {
            if (top.hibermod.ctdapi.signal.SignalRegistries.system(s.signalSystemId()).isEmpty()) {
                problems.append("\n  - service ").append(s.id())
                    .append(" uses unknown signal system ").append(s.signalSystemId());
            }
        }
        if (problems.length() > 0) {
            throw new IllegalStateException("Service registry validation failed:" + problems);
        }
        frozen = true;
    }

    public static void resetForTests() {
        SERVICES.clear();
        frozen = false;
    }
}
