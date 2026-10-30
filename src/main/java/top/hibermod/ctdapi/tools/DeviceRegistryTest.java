package top.hibermod.ctdapi.tools;

import java.util.Set;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.Rail;
import top.hibermod.ctdapi.device.Devices;

/**
 * 设备注册表的规矩。
 *
 * <h2>为什么值得测</h2>
 * 设备注册表有三条容易写错的规则，写错了都不会报错，只会在半路出问题：
 *
 * <ol>
 *   <li><b>冻结之后不许再注册</b>。不冻结的话，运行期注册会让「同一份存档在不同
 *       启动顺序下解析出不同的设备」——排障时几乎无法复现。</li>
 *   <li><b>重复 id 必须拒绝</b>，不能后者覆盖前者。覆盖了等于某个模组的设备被
 *       另一个模组悄悄顶掉。</li>
 *   <li><b>{@code DeviceSite} 必须属于地面或车载之一</b>。两边都为空的设备
 *       既查不到位置也找不到列车，等于凭空存在的幽灵。</li>
 * </ol>
 */
public final class DeviceRegistryTest {

    private DeviceRegistryTest() {}

    public static void main(String[] args) {
        int failures = 0;

        Devices.unfreeze();
        Devices.resetForTests();

        // ① 正常注册与查询
        Devices.DeviceType door = type("t:door", Devices.Kind.GROUND,
            Set.of(Devices.Capabilities.PLATFORM_DOOR), true, Set.of());
        Devices.register(door);

        failures += check("注册后能查到", Devices.type(door.id()).isPresent());
        failures += check("数量正确", Devices.types().size() == 1,
            "size=" + Devices.types().size());

        // ② 重复 id 必须拒绝
        boolean rejected = false;
        try {
            Devices.register(type("t:door", Devices.Kind.GROUND, Set.of(), false, Set.of()));
        } catch (IllegalStateException e) {
            rejected = true;
        }
        failures += check("重复 id 被拒绝", rejected);
        failures += check("拒绝之后原来的还在", Devices.type(door.id()).isPresent());

        // ③ 冻结之后不许注册
        Devices.freeze();
        boolean frozenRejected = false;
        try {
            Devices.register(type("t:later", Devices.Kind.ONBOARD, Set.of(), false, Set.of()));
        } catch (IllegalStateException e) {
            frozenRejected = true;
        }
        failures += check("冻结后拒绝注册", frozenRejected);
        failures += check("冻结状态可查", Devices.isFrozen());

        // ④ 解冻 + 移除：热重载的路径
        Devices.unfreeze();
        failures += check("解冻成功", !Devices.isFrozen());
        failures += check("移除已注册的类型", Devices.remove(door.id()));
        failures += check("移除后查不到", Devices.type(door.id()).isEmpty());
        failures += check("重复移除返回 false", !Devices.remove(door.id()));

        // ⑤ 按能力与车载/地面筛选
        Devices.register(type("t:atp", Devices.Kind.ONBOARD,
            Set.of(Devices.Capabilities.ATP), true,
            Set.of(ResourceLocation.parse("ctdapi:mainline"))));
        Devices.register(type("t:bc", Devices.Kind.GROUND,
            Set.of(Devices.Capabilities.AXLE_COUNTER), false, Set.of()));

        failures += check("按能力筛选（ATP）",
            Devices.withCapability(Devices.Capabilities.ATP).size() == 1);
        failures += check("按能力筛选（不存在的能力）",
            Devices.withCapability("no-such-capability").isEmpty());
        failures += check("车载/地面分开统计",
            Devices.onboard().size() == 1 && Devices.ground().size() == 1,
            "onboard=" + Devices.onboard().size() + " ground=" + Devices.ground().size());

        // ⑥ DeviceSite 必须属于地面或车载之一
        boolean siteRejected = false;
        try {
            new Devices.DeviceSite(
                java.util.UUID.randomUUID(),
                ResourceLocation.parse("t:door"),
                null,
                null,
                0.0,
                true,
                new net.minecraft.nbt.CompoundTag());
        } catch (IllegalArgumentException e) {
            siteRejected = true;
        }
        failures += check("两边都为空的设备被拒绝", siteRejected);

        Devices.unfreeze();
        Devices.resetForTests();
        System.out.println();
        if (failures > 0) {
            System.out.println("设备注册表验收失败: " + failures + " 项");
            System.exit(1);
        }
        System.out.println("设备注册表验收通过：重复/冻结/移除/筛选/位置约束");
    }

    private static Devices.DeviceType type(String id, Devices.Kind kind, Set<String> caps,
                                           boolean commandable, Set<ResourceLocation> systems) {
        return new Devices.DeviceType(
            ResourceLocation.parse(id), Component.literal(id), kind, caps, commandable, systems);
    }

    private static int check(String name, boolean ok) {
        return check(name, ok, "");
    }

    private static int check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok ? "" : "  <- " + detail));
        return ok ? 0 : 1;
    }

    @SuppressWarnings("unused")
    private static Rail.EdgeId anyEdge() {
        return null;
    }
}
