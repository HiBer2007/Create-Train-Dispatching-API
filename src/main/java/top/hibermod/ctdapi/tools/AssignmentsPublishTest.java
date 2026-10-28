package top.hibermod.ctdapi.tools;

import java.util.List;

import net.minecraft.resources.ResourceLocation;

import top.hibermod.ctdapi.operation.Assignment;
import top.hibermod.ctdapi.operation.Assignments;
import top.hibermod.ctdapi.operation.Run;
import top.hibermod.ctdapi.operation.UnitNumber;
import top.hibermod.ctdapi.operation.UnitSet;

/**
 * 改派语义的回归测试，专门盯「把最后一辆车摘走」这个边界。
 *
 * <h2>它为什么存在</h2>
 * {@link Assignment} 的构造契约要求至少一个车组。而改派（后来的指派抢走前面的车）
 * 会把车从旧指派里摘出去——**如果旧指派只有那一个车组，摘完就空了**，
 * 硬造一个空指派会抛 {@code IllegalArgumentException}。
 *
 * <p>这个异常不会崩服，但会让整帧调度作废，日志里只留一行
 * 「dispatch tick failed」。真实场景就是「把某列车从 A 交路改派到 B 交路」。
 */
public final class AssignmentsPublishTest {

    private AssignmentsPublishTest() {}

    private static final ResourceLocation RUN_A = id("run_a");
    private static final ResourceLocation RUN_B = id("run_b");

    public static void main(String[] args) {
        int failures = 0;

        failures += check("首次发布成功", publish("a1", RUN_A, set("s1", "T1")) != null);
        failures += check("注册表里有 1 条", Assignments.size() == 1);

        // ① 核心用例：把唯一一辆车改派走，旧指派应当整个消失
        try {
            publish("b1", RUN_B, set("s2", "T1"));
            failures += check("改派不抛异常", true);
            failures += check("旧指派已被撤掉", Assignments.get(id("a1")).isEmpty(),
                String.valueOf(Assignments.size()));
            failures += check("新指派存在", Assignments.get(id("b1")).isPresent());
            failures += check("该车归新指派", Assignments.ofUnit(new UnitNumber("T1"))
                .stream().anyMatch(a -> a.id().equals(id("b1"))));
        } catch (Throwable t) {
            failures += check("改派不抛异常", false, t.toString());
        }

        // ② 多车组时只摘该车，其余保留
        try {
            publish("c1", RUN_A, set("s3", "T1", "T2"));
            publish("d1", RUN_B, set("s4", "T1"));
            var c = Assignments.get(id("c1")).orElse(null);
            failures += check("原指派仍存在", c != null);
            if (c != null) {
                failures += check("只摘走了 T1，T2 还在",
                    c.allUnits().size() == 1
                        && c.allUnits().get(0).value().equals("T2"),
                    c.allUnits().toString());
            }
        } catch (Throwable t) {
            failures += check("多车组摘除不抛异常", false, t.toString());
        }

        // ③ 撤销之后可以被重新占用
        try {
            Assignments.revoke(id("d1"));
            publish("e1", RUN_A, set("s5", "T1"));
            failures += check("撤销后可重新指派", Assignments.get(id("e1")).isPresent());
        } catch (Throwable t) {
            failures += check("撤销后可重新指派", false, t.toString());
        }

        System.out.println();
        if (failures > 0) {
            System.out.println("改派语义测试失败: " + failures + " 项");
            System.exit(1);
        }
        System.out.println("改派语义测试通过：摘空即撤，其余保留，撤销后可重新占用");
    }

    private static Assignment publish(String path, ResourceLocation run, UnitSet set) {
        Assignment a = new Assignment(id(path),
            new Run(run, "T" + path, 0), List.of(set), Assignment.Source.KERNEL, 0);
        Assignments.publish(a);
        return Assignments.get(id(path)).orElse(null);
    }

    private static UnitSet set(String path, String... units) {
        List<UnitNumber> list = new java.util.ArrayList<>();
        for (String u : units) list.add(new UnitNumber(u));
        return new UnitSet(id(path), net.minecraft.network.chat.Component.literal(path), list);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("ctdapi", path);
    }

    private static int check(String name, boolean ok) {
        return check(name, ok, "");
    }

    private static int check(String name, boolean ok, String detail) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok ? "" : "  <- " + detail));
        return ok ? 0 : 1;
    }
}
