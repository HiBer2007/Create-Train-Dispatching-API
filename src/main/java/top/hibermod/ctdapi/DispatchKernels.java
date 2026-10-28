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

    /** 每次 register / replace / remove 递增，供宿主发现「同名工厂被换掉了」。 */
    private static volatile long revision;

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
        revision++;
    }

    /**
     * 注册表的修订号，每次 register / replace / remove 都 +1。
     *
     * <p><b>为什么需要它</b>：注册表里存的是**工厂**，宿主拿不到「实例被换掉了」这件事。
     * 热重载换掉同名工厂时，宿主按 id 判断不出变化，会一直握着绑在旧实例上的 pass——
     * 表现为「重载成功，但行为没变」，而且旧脚本还在跑。
     *
     * <p>宿主只需要记住上次见到的修订号，变了就重建全部 pass。
     */
    public static long revision() {
        return revision;
    }

    /** 是否存在该内核。 */
    public static boolean isRegistered(ResourceLocation id) {
        return FACTORIES.containsKey(id);
    }

    /**
     * 替换一个已注册的内核工厂（**热重载专用**）。
     *
     * <p>与 {@link #register} 的区别：重复 id 不抛异常，而是换掉工厂。
     * 只有脚本/配置热重载才该用它——正常注册仍然走 {@code register}，
     * 否则「重复注册」这个最有价值的早期错误就被吞掉了。
     *
     * <p>宿主负责在替换后调用旧实例的 {@code onDetach()} 与新实例的 {@code onAttach(env)}。
     *
     * @return 是否存在旧的注册
     */
    public static boolean replace(ResourceLocation id, Supplier<DispatchKernel> factory) {
        if (id == null || factory == null) {
            throw new IllegalArgumentException("id and factory must be non-null");
        }
        boolean existed = FACTORIES.put(id, factory) != null;
        revision++;
        return existed;
    }

    /**
     * 注销一个内核（**热重载专用**）。
     *
     * @return 是否存在过
     */
    public static boolean remove(ResourceLocation id) {
        boolean existed = FACTORIES.remove(id) != null;
        if (existed) revision++;
        return existed;
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
