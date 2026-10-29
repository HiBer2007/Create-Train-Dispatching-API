package top.hibermod.ctdapi.signal;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

/**
 * 「这架信号机服务于哪套信号制度」。
 *
 * <h2>为什么需要它</h2>
 * 一套线路上可能同时存在传统固定闭塞与 CBTC。一架信号机如果同时显示两套制度的状态，
 * 司机无法判断该信谁——现实里也是按制式分区设信号的。
 *
 * <h2>为什么做成接口而不是一张外部表</h2>
 * 「属于哪套制度」是信号机自己的属性，跟着它走：拆下来重放、存档读档、
 * 网络同步，都应当和信号机本身一起。放在外部的表里，就会出现
 * 「信号机没了，表里还留着一条」，而且客户端根本读不到。
 *
 * <p>实现方（宿主模组）通过 mixin 把这两个方法挂到 Create 的信号边点上。
 */
public interface SignalSystemHolder {

    /** 所属制度的 id；未指定返回 {@code null}。 */
    @Nullable
    ResourceLocation ctd$signalSystem();

    /** 指定所属制度；传 {@code null} 表示不归任何制度管。 */
    void ctd$setSignalSystem(@Nullable ResourceLocation systemId);

    /** 从任意对象上取能力；不支持返回 {@code null}。 */
    @Nullable
    static SignalSystemHolder of(@Nullable Object target) {
        return target instanceof SignalSystemHolder holder ? holder : null;
    }

    /** 取制度 id 的字符串形式；拿不到返回空串（方便脚本判断）。 */
    static String systemOf(@Nullable Object target) {
        SignalSystemHolder holder = of(target);
        if (holder == null) return "";
        ResourceLocation id = holder.ctd$signalSystem();
        return id == null ? "" : id.toString();
    }
}
