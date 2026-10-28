package top.hibermod.ctdapi.signal;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 一套**信号制度**（signal system）。
 *
 * <p>这是「多条铁路用不同信号制度」的承载单位。现实中：干线四显示自动闭塞、调车场白蓝灯、
 * 城际三显示、地铁固定闭塞——它们是**不同的制度**，不是同一种制度的不同机型。
 *
 * <p>本 API 允许它们**同时存在**：
 * <ul>
 *   <li>每台信号机属于且只属于一个 {@code SignalSystem}（见 {@link SignalType#systemId()}）；</li>
 *   <li>同一张轨道网上可以有来自多个系统的信号机；</li>
 *   <li>具体用哪套由**时刻表 / 调度引擎**决定——它决定「这段路按什么规则跑」，
 *       而不是由玩家全局开关决定。</li>
 * </ul>
 *
 * <p>系统本身只做**分组与命名空间**：语义（{@link Aspect}）与机型（{@link SignalType}）
 * 都挂在系统下。行车规则在语义里（限速 + 类别），不在系统里——这样同一套语义
 * 可以被多个系统复用。
 *
 * @param id          稳定标识，**一旦发布不可改名**（方块 NBT 里存的就是它）
 * @param displayName 显示名
 * @param palette     该系统用到的灯色（渲染器据此准备灯罩）
 * @param aspectIds   该系统定义的语义 id
 * @param typeIds     该系统定义的机型 id
 * @param fallbackType 未知机型时的回退机型 id（必须是本系统的机型）
 */
public record SignalSystem(
    ResourceLocation id,
    Component displayName,
    List<LampColor> palette,
    Set<ResourceLocation> aspectIds,
    Set<ResourceLocation> typeIds,
    ResourceLocation fallbackType
) {

    public SignalSystem {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(fallbackType, "fallbackType");
        palette = List.copyOf(palette);
        aspectIds = Set.copyOf(aspectIds);
        typeIds = Set.copyOf(typeIds);

        if (typeIds.isEmpty()) {
            throw new IllegalArgumentException("SignalSystem " + id + " has no signal types");
        }
        if (!typeIds.contains(fallbackType)) {
            throw new IllegalArgumentException(
                "SignalSystem " + id + " fallback " + fallbackType + " is not one of its types");
        }
    }

    public boolean ownsAspect(ResourceLocation aspectId) {
        return aspectIds.contains(aspectId);
    }

    public boolean ownsType(ResourceLocation typeId) {
        return typeIds.contains(typeId);
    }

    public static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    /** 链式构造器。 */
    public static final class Builder {
        private final ResourceLocation id;
        private Component displayName;
        private final List<LampColor> palette = new java.util.ArrayList<>();
        private final Set<ResourceLocation> aspects = new java.util.LinkedHashSet<>();
        private final Set<ResourceLocation> types = new java.util.LinkedHashSet<>();
        private ResourceLocation fallback;

        private Builder(ResourceLocation id) {
            this.id = id;
        }

        public Builder name(Component name) {
            this.displayName = name;
            return this;
        }

        public Builder palette(LampColor... colors) {
            palette.addAll(List.of(colors));
            return this;
        }

        public Builder aspect(Aspect aspect) {
            aspects.add(aspect.id());
            return this;
        }

        public Builder type(SignalType type) {
            types.add(type.id());
            return this;
        }

        public Builder fallback(SignalType type) {
            types.add(type.id());
            this.fallback = type.id();
            return this;
        }

        public SignalSystem build() {
            if (displayName == null) {
                displayName = Component.translatable(
                    "signal_system." + id.getNamespace() + "." + id.getPath());
            }
            if (fallback == null) {
                if (types.isEmpty()) throw new IllegalStateException("SignalSystem " + id + " has no types");
                fallback = types.iterator().next();
            }
            return new SignalSystem(id, displayName, palette, aspects, types, fallback);
        }
    }

    @Override
    public String toString() {
        return "SignalSystem[" + id + ", types=" + typeIds.size() + "]";
    }
}
