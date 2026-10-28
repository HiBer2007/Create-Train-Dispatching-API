package top.hibermod.ctdapi.operation;

import java.util.Objects;

/**
 * 车辆编号。
 *
 * <p><b>这是物理身份，不是车次。</b> 一列车有且只有一个车辆编号（对应 Create 的
 * {@code Train.name}），但它一天可以跑很多个车次。把两者混为一谈是最常见的建模错误：
 * 那样「同一列车换了车次」就变成了「换了一列车」，车厢、里程、故障历史全断。
 *
 * <p>字符串形式是刻意的：工具链（时刻表编辑、Web 调度台、纸质运行图）都按字符串处理，
 * 强制它们理解 Java 对象没有好处。
 *
 * @param value 编号原文，例如 {@code "CRH2A-2013"} 或 {@code "上行-03"}
 */
public record UnitNumber(String value) implements Comparable<UnitNumber> {

    public UnitNumber {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("unit number must not be blank");
    }

    @Override
    public int compareTo(UnitNumber o) {
        return value.compareTo(o.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
