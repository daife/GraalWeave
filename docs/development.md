# 开发与构建

项目分为三个模块：`core` 提供 GraalPy 环境、会话、Java 互操作、API 探索与本机协议；`mod` 接入 Fabric 生命周期、游戏线程和事件；`cli` 提供终端与 stdio MCP 入口。Python 辅助 API 位于 `core/src/main/resources/python/graalweave`。

使用 Java 25，将 `JAVA_HOME` 指向 JDK 目录，或将其 `bin` 加入 `PATH`。项目使用 Gradle Wrapper 9.8.2、Fabric Loom 1.19.3，以及 GraalPy / Polyglot 25.4.4.1.1；Minecraft 26.3 使用原始名称，不使用 Yarn 或混淆映射。

```powershell
.\gradlew.bat :mod:build
```

发布产物为 `mod/build/libs/graalweave-0.1.1.jar`，已包含运行所需依赖。

```powershell
.\gradlew.bat :core:integration
.\gradlew.bat :cli:integration
.\gradlew.bat :cli:installationIntegration
```

分别验证核心集成、CLI/MCP，以及从发布 JAR 自动释放工具、初始化环境、保留用户文件和清理旧依赖的完整流程。前两项可加 `-PcoreOnly`，仅配置运行时和 CLI。

Agent 编程约定见 [Agent 工作方式](agent.md)。
