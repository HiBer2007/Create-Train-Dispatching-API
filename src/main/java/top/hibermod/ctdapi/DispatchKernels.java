package top.hibermod.ctdapi;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;

/**
 * 调度内核注册表。
 *
 * <p>这不是 NeoForge 的 registry——它不冻结、不参与数据包生成，只是宿主的一个查找表。
 * 注册时机：**mod 构造期或 {@code FMLCommonSetupEvent}**，早于任何世界加载。
 *
 * <p>同一个 JAR 可以注册多个内核；最终启用哪一个由玩家在 host 配置里按 {@link ResourceLocation} 选择。
 */
public final class DispatchKernels {

    private DispatchKernels() {}

    private static final Map<ResourceLocation, Supplier<DispatchKernel>> FACTORIES = new ConcurrentHashMap<>();

    /**
     * 注册一个内核工厂。
     *
     * @param id      内核标识，**必须**在自己的命名空间下（如 {@code mymod:interlocking}）
     * @param factory 工厂。同一时刻只会有一个实例存活，但宿主可能因配置切换而重建它。
     * @throws IllegalStateException 同一 id 重复注册
     */
    public static void register(ResourceLocation id, Supplier<DispatchKernel> factory) {
        if (id == null || factory == null) {
            throw new IllegalArgumentException("id and factory must be non-null");
        }
        if (FACTORIES.putIfAbsent(id, factory) != null) {
            throw new IllegalStateException("Duplicate dispatch kernel id: " + id);
        }
    }

    /** 是否存在该内核。 */
    public static boolean isRegistered(ResourceLocation id) {
        return FACTORIES.containsKey(id);
    }

    /** 全部已注册内核 id（快照）。 */
    public static java.util.Set<ResourceLocation> registeredIds() {
        return java.util.Set.copyOf(FACTORIES.keySet());
    }

    /**
     * 创建一个内核实例。宿主内部使用，第三方一般不需要调用。
     *
     * @return 未注册时返回 {@link Optional#empty()}
     */
    public static Optional<DispatchKernel> create(ResourceLocation id) {
        Supplier<DispatchKernel> factory = FACTORIES.get(id);
        return factory == null ? Optional.empty() : Optional.ofNullable(factory.get());
    }
}
