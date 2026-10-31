# GraalWeave

让 Python 和你的 Agent 直接进入 Minecraft。

GraalWeave 将 GraalPy 嵌入 Minecraft JVM，让你用原生 Python 语法直接操作游戏和已安装模组的 Java 对象。你可以在终端编写脚本，也可以通过内置 MCP，让 Agent 探索 API、编写工具并执行任务。

## 特点

- **高度可扩展**：直接访问原版、Fabric API 和第三方模组暴露的公共 Java API，无需为每个模组编写专用桥接。
- **运行时调用重定向**：依托 GraalPy 的 Java 互操作，结合类加载与反射替换处理对象，在特定调用路径上实现类似 Mixin 的拦截、追加或替换行为。[详解](docs/scripting.md#运行时调用重定向与类-mixin-效果)
- **Python 脚本热重载**：无需重启游戏，修改脚本后重新执行；已导入模块可重置会话后重新导入，适合 Agent 持续迭代。
- **简单的 MCP 接入**：一个 `graalweave` 工具即可执行代码、运行脚本、管理会话；持续会话保留变量与导入。
- **运行中探索 API**：查询当前实例的模组、Java 类和成员，根据实际安装的模组发现可用能力。
- **游戏事件与调度**：支持主线程调用、tick 等待、事件订阅和启动脚本。

GraalWeave 提供脚本与 Java 互操作基础设施，寻路、移动、采集等能力可通过脚本和其他模组的 API 组合，例如 [Baritone](examples/baritone.py)。

## 安装

环境：**Minecraft 26.3 · Fabric Loader 0.20.1 · Fabric API 0.165.0+26.3 · Windows x64 · Java 25**，推荐使用 GraalVM 25。

将 Release 中的 `graalweave-0.1.1.jar` 放入实例的 `mods` 文件夹，启动游戏即可。首次启动会自动准备 Python 环境、终端工具和 MCP 配置，无需另装 Python。

## 通过 MCP 使用

1. 启动游戏后，打开 `<实例目录>/graalweave/mcp.json`。
2. 将配置复制或导入支持 **stdio MCP** 的 Agent 工具。使用 `mcpServers` 格式的工具合并其中的 `graalweave` 条目；其他格式填写相同的 `command` 和 `args`。
3. 重新加载 MCP，确认出现 `graalweave` 工具，然后让 Agent 开始探索：

> 检查游戏连接，列出已安装模组，再读取我的玩家坐标。

涉及玩家和世界的操作需要先进入世界。默认工作空间为 `<实例目录>/graalweave/workspace`，可在其中存放可复用的 Python 模块。

工具参数、会话管理与工作目录配置见 [MCP 使用说明](docs/mcp.md)。

## 通过终端使用

在 PowerShell 中运行实例下的终端入口：

```powershell
& '<实例目录>\graalweave\client\bin\graalweave.bat' repl
```

进入世界后，即可在 REPL 中执行 Python：

```python
import java
from graalweave import client

mc = java.type('net.minecraft.client.Minecraft').getInstance()
print(client.call(lambda: str(mc.player.position())))
```

运行脚本、标准输入、命名会话与包管理见 [终端使用说明](docs/cli.md)。

## 脚本与 API

- [Python 脚本指南](docs/scripting.md)：模块复用、启动脚本、游戏线程、事件与调用重定向。
- [Java API 探索](docs/api-explorer.md)：查找模组、类、方法与字段。
- [Agent 工作方式](docs/agent.md)：探索与执行任务的编程约定。
- [示例脚本](examples/)：游戏状态、事件、寻路与战斗。

## 开发

使用 Java 25 构建：

```powershell
.\gradlew.bat :mod:build
```

产物位于 `mod/build/libs/graalweave-0.1.1.jar`。项目结构、依赖和集成验证见 [开发文档](docs/development.md)。
