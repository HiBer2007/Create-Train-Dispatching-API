# 离线结构自检
#
# 背景：本仓库的正式构建依赖 NeoForge maven（maven.neoforged.net）。
# 该站点在部分网络下 **TCP 层不可达**（不是仓库配置问题，换镜像列表救不了），
# 因此无法在这里验证「能否编译」。
#
# 本脚本不解决那个问题。它做的是另一件事：
#   用 4 个 Minecraft 类型的**最小桩**把 API 源码编译一遍，
#   用来抓出**我们自己的**语法/类型/签名不一致（例如某个方法名写错、少个参数）。
#
# ⚠ 它**不校验**与真实 Minecraft 的 API 兼容性——桩是按我们用到的那几个成员写的。
#    正式构建仍然必须联网跑 `gradlew build`。
#
# 用法：pwsh -File tools/verify-offline.ps1

$ErrorActionPreference = 'Stop'

$repo = Split-Path $PSScriptRoot -Parent
$src  = Join-Path $repo 'src\main\java'
if (-not (Test-Path $src)) { throw "找不到源码目录: $src" }

$work = Join-Path $repo 'build\offline-verify'
$stub = Join-Path $work 'stubs'
$out  = Join-Path $work 'classes'
if (Test-Path $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stub, $out | Out-Null

# ── 桩：只包含 API 实际用到的成员 ──────────────────────────────────────────

$files = @{
    'net\minecraft\resources\ResourceLocation.java' = @'
package net.minecraft.resources;

import java.util.Objects;

/** 离线桩：仅用于结构自检。 */
public final class ResourceLocation {
    private final String namespace;
    private final String path;

    private ResourceLocation(String namespace, String path) {
        this.namespace = namespace;
        this.path = path;
    }

    public static ResourceLocation fromNamespaceAndPath(String namespace, String path) {
        return new ResourceLocation(namespace, path);
    }

    public static ResourceLocation parse(String id) {
        int i = id.indexOf(':');
        return i < 0
            ? new ResourceLocation("minecraft", id)
            : new ResourceLocation(id.substring(0, i), id.substring(i + 1));
    }

    public String getNamespace() { return namespace; }
    public String getPath() { return path; }

    @Override public boolean equals(Object o) {
        return o instanceof ResourceLocation r
            && r.namespace.equals(namespace) && r.path.equals(path);
    }

    @Override public int hashCode() { return Objects.hash(namespace, path); }
    @Override public String toString() { return namespace + ":" + path; }
}
'@

    'net\minecraft\network\chat\Component.java' = @'
package net.minecraft.network.chat;

/** 离线桩：仅用于结构自检。 */
public interface Component {

    static Component literal(String text) {
        return new Literal(text);
    }

    static Component translatable(String key, Object... args) {
        return new Translatable(key, args);
    }

    String getString();

    record Literal(String text) implements Component {
        @Override public String getString() { return text; }
    }

    record Translatable(String key, Object[] args) implements Component {
        @Override public String getString() { return key; }
    }
}
'@

    'net\minecraft\nbt\CompoundTag.java' = @'
package net.minecraft.nbt;

import java.util.HashMap;
import java.util.Map;

/** 离线桩：仅用于结构自检。 */
public final class CompoundTag {

    private final Map<String, Object> values = new HashMap<>();

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public CompoundTag getCompound(String key) {
        Object v = values.get(key);
        return v instanceof CompoundTag t ? t : new CompoundTag();
    }

    public void putCompound(String key, CompoundTag value) { values.put(key, value); }

    public void putLong(String key, long value) { values.put(key, value); }
    public long getLong(String key) {
        Object v = values.get(key);
        return v instanceof Long l ? l : 0L;
    }

    public void putInt(String key, int value) { values.put(key, value); }
    public int getInt(String key) {
        Object v = values.get(key);
        return v instanceof Integer i ? i : 0;
    }

    public void putString(String key, String value) { values.put(key, value); }
    public String getString(String key) {
        Object v = values.get(key);
        return v instanceof String s ? s : "";
    }

    public void putBoolean(String key, boolean value) { values.put(key, value); }
    public boolean getBoolean(String key) {
        Object v = values.get(key);
        return v instanceof Boolean b && b;
    }

    public CompoundTag copy() {
        CompoundTag t = new CompoundTag();
        t.values.putAll(values);
        return t;
    }

    @Override public String toString() { return "CompoundTag" + values.keySet(); }
}
'@

    'net\minecraft\resources\ResourceKey.java' = @'
package net.minecraft.resources;

/** 离线桩：仅用于结构自检（只作为类型参数出现）。 */
public final class ResourceKey<T> {
    private final ResourceLocation location;

    private ResourceKey(ResourceLocation location) {
        this.location = location;
    }

    public ResourceLocation location() {
        return location;
    }
}
'@

    'net\minecraft\world\level\Level.java' = @'
package net.minecraft.world.level;

/** 离线桩：仅用于结构自检（只作为类型参数出现）。 */
public class Level {
    protected Level() {}
}
'@

    'org\jetbrains\annotations\Nullable.java' = @'
package org.jetbrains.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 离线桩：仅用于结构自检。 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER,
          ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE })
public @interface Nullable {
    String value() default "";
}
'@
}

foreach ($kv in $files.GetEnumerator()) {
    $p = Join-Path $stub $kv.Key
    New-Item -ItemType Directory -Force -Path (Split-Path $p -Parent) | Out-Null
    [IO.File]::WriteAllText($p, $kv.Value, (New-Object Text.UTF8Encoding($false)))
}

# ── 编译 ──────────────────────────────────────────────────────────────────

$javac = (Get-Command javac -ErrorAction Stop).Source
$sources = @(Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object { $_.FullName })

Write-Host "桩文件: $($files.Count)  API 源文件: $($sources.Count)"
Write-Host "javac: $javac"

$argFile = Join-Path $work 'sources.txt'
$all = @(Get-ChildItem $stub -Recurse -Filter *.java | ForEach-Object { $_.FullName }) + $sources
[IO.File]::WriteAllLines($argFile, $all, (New-Object Text.UTF8Encoding($false)))

& $javac -encoding UTF-8 --release 21 -Xlint:all -d $out "@$argFile" 2>&1 | ForEach-Object { Write-Host $_ }

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "结构自检失败（exit $LASTEXITCODE）"
    exit $LASTEXITCODE
}

$classes = (Get-ChildItem $out -Recurse -Filter *.class | Measure-Object).Count
Write-Host ""
Write-Host "结构自检通过：$classes 个 class 已生成"
Write-Host "注意：这不代表能与真实 Minecraft API 兼容，正式构建仍需联网执行 gradlew build"
