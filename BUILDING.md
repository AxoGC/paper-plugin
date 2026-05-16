# 构建 PaperPlatform（Java 21 / Java 25 双版本）

我们维护两个 Paper 服务器：一个跑在 Java 21（Paper 1.21.x），一个跑在 Java 25（Paper 26.x）。两套 API 互不兼容，因此插件 jar 必须分别构建，**不能跨服复用**。

构建变体由 Gradle 属性 `targetJava` 切换。默认是 Java 25。

## 环境

- JDK 25（构建时用；`JAVA_HOME` 指向它即可）
- Gradle 9.x（系统已装；项目没有 wrapper）
- 联网（首次会从 papermc.io 拉 Paper API）

> 即使要构建 Java 21 变体，本机 JDK 也必须有 25 —— Gradle 工具链会自己挑合适的 JDK 编译。

```bash
export JAVA_HOME=/root/.sdkman/candidates/java/25.0.3-tem
export PATH=$JAVA_HOME/bin:$PATH
```

## 命令

在 `/root/paper-plugin` 目录下：

```bash
# Java 25 / Paper 26.x（默认）
gradle build

# Java 21 / Paper 1.21.x
gradle -PtargetJava=21 build
```

## 产物

| 命令 | 输出文件 | 部署目标 |
|---|---|---|
| `gradle build` | `build/libs/paper-platform-0.1.0.jar` | Java 25 / Paper 26.x 服 |
| `gradle -PtargetJava=21 build` | `build/libs/paper-platform-0.1.0-java21.jar` | Java 21 / Paper 1.21.x 服 |

Java 21 变体带 `-java21` 后缀，所以两个 jar 可以共存于 `build/libs/`，不会互相覆盖。

## 一次构建两个

按顺序执行即可，**不要 clean**：

```bash
gradle build                   # 生成 Java 25 jar
gradle -PtargetJava=21 build   # 生成 Java 21 jar
ls build/libs/                 # 应同时看到两个 jar
```

注意：`gradle clean` 会删掉 `build/`，**两个 jar 同时消失**。clean 之后要补哪个，自己重新跑对应命令。

## 部署

1. 把对应版本的 jar 复制到该服务器的 `plugins/` 目录：
   - Java 25 服 → `paper-platform-0.1.0.jar`
   - Java 21 服 → `paper-platform-0.1.0-java21.jar`
2. 重启服务器（或用 PlugMan 之类的工具重载，不推荐生产用）。
3. 第一次启动会生成 `plugins/PaperPlatform/config.yml`，把 `base_url` 和 `token` 填好后再重启一次。

## 装错版本会怎样

- Java 25 jar 放到 Java 21 服：启动时 `UnsupportedClassVersionError`，插件加载失败，服务器照常启动但功能缺失。
- Java 21 jar 放到 Java 26 服：能加载，但调用某些被移除/变更的 Paper API 时会在运行时抛 `NoSuchMethodError`。

两种都会在日志里报错，照报错信息切换 jar 即可。

## 改了哪里

`build.gradle.kts` 顶部有这段：

```kotlin
val targetJava: String = (findProperty("targetJava") as String?) ?: "25"
val isJava21Build = targetJava == "21"
```

`paperApiCoord`、JDK toolchain、Kotlin `jvmTarget`、Java `release`、jar 文件名分类符都跟着这个变量走。要调版本号或新增变体，改这里。
