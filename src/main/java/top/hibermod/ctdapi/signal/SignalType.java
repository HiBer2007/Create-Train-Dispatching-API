package top.hibermod.ctdapi.signal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 一种**信号机类型**（进站 / 出站 / 区间通过 / 调车 / 预告 / 自定义……）。
 *
 * <p>这是开放注册的核心：类型同时决定「这台机器长什么样」和「它怎么表达各个语义」。
 * 不做成枚举，因为不同铁路的信号制度差异极大：
 *
 * <ul>
 *   <li>中国 4 显示：绿 / 黄 / 双黄 / 红</li>
 *   <li>英国 3 显示：绿 / 黄 / 红（另有黄闪 restrictive）</li>
 *   <li>日本：3 或 5 显示，另有紫色中继</li>
 *   <li>调车：白 / 蓝</li>
 * </ul>
 *
 * <p>{@link Aspect} 是跨机型共享的**语义**，{@link #aspectMap()} 是「本机型如何表达该语义」。
 * 所以「通行」在四显示机上亮 4 号灯、在三显示机上亮 3 号灯，但内核说的是同一个
 * {@code tcm:proceed}。
 *
 * @param id           稳定标识，**一旦发布不可改名**（方块 NBT 存的就是它）
 * @param displayName  显示名
 * @param lampLayout   灯座布局。下标 0 是第一个灯座
 * @param aspectMap    Aspect id → 本机型上的灯位与模式
 * @param semantics    联锁语义
 * @param priority     与其它信号机同位置时的显示优先级（大的优先）
 */
public record SignalType(
    ResourceLocation id,
    ResourceLocation systemId,
    Component displayName,
    List<LampSpec> lampLayout,
    Map<ResourceLocation, List<LampDisplay>> aspectMap,
    SignalSemantics semantics,
    int priority
) {

    public SignalType {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(semantics, "semantics");
        lampLayout = List.copyOf(lampLayout);
        aspectMap = Map.copyOf(aspectMap);

        if (lampLayout.isEmpty()) {
            throw new IllegalArgumentException("SignalType " + id + " needs at least one lamp");
        }
        int count = lampLayout.size();
        for (var entry : aspectMap.entrySet()) {
            for (LampDisplay d : entry.getValue()) {
                if (d.slot() >= count) {
                    throw new IllegalArgumentException(
                        "SignalType " + id + " maps aspect " + entry.getKey()
                            + " to slot " + d.slot() + " but only has " + count + " lamps");
                }
            }
        }
    }

    /** 灯座数量。 */
    public int lampCount() {
        return lampLayout.size();
    }

    /** 该机型能否表达某个语义。 */
    public boolean supports(ResourceLocation aspectId) {
        return aspectMap.containsKey(aspectId);
    }

    /** 某个语义在本机型上点亮哪些灯座；不支持时返回空列表。 */
    public List<LampDisplay> displaysFor(ResourceLocation aspectId) {
        return aspectMap.getOrDefault(aspectId, List.of());
    }

    /** 该机型是否作为停车点（预告机不是）。 */
    public boolean isStoppingPoint() {
        return semantics.stoppingPoint();
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    /** 链式构造器。 */
    public static final class Builder {
        private final ResourceLocation id;
        private ResourceLocation systemId;
        private Component displayName;
        private final List<LampSpec> lamps = new ArrayList<>();
        private final Map<ResourceLocation, List<LampDisplay>> aspectMap = new LinkedHashMap<>();
        private SignalSemantics semantics = SignalSemantics.BLOCK;
        private int priority = 0;

        private Builder(ResourceLocation id) {
            this.id = id;
        }

        /** 归属的信号制度。未设置时归入默认系统。 */
        public Builder system(ResourceLocation systemId) {
            this.systemId = systemId;
            return this;
        }

        public Builder system(SignalSystem system) {
            return system(system.id());
        }

        public Builder name(Component name) {
            this.displayName = name;
            return this;
        }

        /** 追加一个灯座。调用顺序就是灯座下标顺序。 */
        public Builder lamp(LampSpec spec) {
            lamps.add(spec);
            return this;
        }

        public Builder lamp(LampColor color) {
            return lamp(LampSpec.steady(color));
        }

        /**
         * 声明「本机型用哪些灯座表达某个语义」。
         *
         * @param aspect 语义 id（如 {@code tcm:proceed}）
         * @param slots  灯座下标，按需要的模式
         */
        public Builder expresses(ResourceLocation aspect, LampDisplay... slots) {
            aspectMap.put(aspect, List.of(slots));
            return this;
        }

        /** 便捷写法：常亮若干灯座。 */
        public Builder expresses(ResourceLocation aspect, int... steadySlots) {
            List<LampDisplay> list = new ArrayList<>(steadySlots.length);
            for (int s : steadySlots) list.add(LampDisplay.steady(s));
            aspectMap.put(aspect, list);
            return this;
        }

        public Builder semantics(SignalSemantics semantics) {
            this.semantics = semantics;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public SignalType build() {
            if (displayName == null) {
                displayName = Component.translatable(
                    "signal_type." + id.getNamespace() + "." + id.getPath());
            }
            if (lamps.isEmpty()) {
                throw new IllegalStateException("SignalType " + id + " needs at least one lamp");
            }
            if (systemId == null) {
                systemId = SignalRegistries.DEFAULT_SYSTEM_ID;
            }
            return new SignalType(id, systemId, displayName, lamps, aspectMap, semantics, priority);
        }
    }

    @Override
    public String toString() {
        return "SignalType[" + id + ", lamps=" + lampLayout.size() + "]";
    }
}
