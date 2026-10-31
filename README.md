# GraalWeave

Minecraft 26.3 Fabric 客户端内的 Python 基础设施。Python 在游戏 JVM 中运行，使用 GraalPy 原生 Java 互操作读取 Minecraft、Fabric API 和其他 Mod 的公开 API。

## 构建与安装

需要 Java 25（推荐 GraalVM 25），将 `JAVA_HOME` 指向 JDK 目录，或将其 `bin` 目录加入 `PATH`。

```powershell
.\gradlew.bat offlineZip
```

核心集成检查：`gradlew.bat :core:integration`；CLI 标准输入检查：`gradlew.bat :cli:integration`。可加 `-PcoreOnly`，仅配置运行时和 CLI。

解压 `build/distributions/GraalWeave-0.1.0-windows-x64.zip`，在解压目录运行：

```powershell
.\install.ps1 -Instance '<实例目录>'
```

加 `-Codex` 可同时把内置 MCP 注册到本机 Codex。安装器还会生成实例下的 `graalweave/mcp.json`，供其他支持 stdio MCP 的客户端使用。

安装包带齐运行时依赖；初始化在安装位置创建 venv，不需要下载 Python 或 pip。Minecraft 实例需要 Fabric Loader 0.20.1 和 Fabric API。安装脚本记录所选 JDK 路径，之后使用 CLI 无需另配 JAVA_HOME；已有 JAVA_HOME 时以该环境变量为准。安装器默认使用 `JAVA_HOME`，未设置时从 `PATH` 查找 Java；也可通过 `-JavaHome <JDK目录>` 指定。构建同样需要 Java 25。

## 使用

Agent 优先使用内置 MCP 的单个 `graalweave` 工具，优先导入可复用模块，再直接调用函数：

```json
{"code":"from graalweave import client\nimport java\nmc = java.type('net.minecraft.client.Minecraft').getInstance()\nprint(client.call(lambda: str(mc.player.position())))"}
{"script":"explore.py","args":["中文"]}
{"action":"status"}
```

默认保留 `agent` 会话的变量，可用 `session` 指定其他会话；`action` 还支持 `sessions`、`interrupt`、`close`、`reset`。代码和脚本二选一，`cwd` 初始默认是实例下的 `graalweave/workspace`，脚本的相对路径相对于 `cwd`。传入 `cwd` 会立即保存到 `graalweave/mcp-settings.json`，后续调用无需重复传入；配置只在 MCP 启动时读取一次。执行结果直接返回 stdout 文本；stderr 单独标注，失败时附带必要错误信息和退出码，是否失败由 `isError` 表示；`input()` 收到 EOF。MCP 支持取消正在执行的调用。

服务入口是 `gw --instance '<实例目录>' mcp`。安装器生成的 MCP 配置直接启动 Java，不经过 PowerShell 或 cmd，也不需要 agent 设置终端编码。连接配置及示例见 [MCP](docs/mcp.md)。

启动游戏后，使用安装目录内的 `graalweave/client/bin/graalweave.bat`。以下将它简称为 `gw`：

```powershell
gw --instance '<实例目录>' status
gw --instance '<实例目录>' run '.\examples\explore.py' arg1
gw --instance '<实例目录>' -m my_package arg1
gw --instance '<实例目录>' --session exploration repl
gw --instance '<实例目录>' --session exploration run '.\inspect.py'
gw --instance '<实例目录>' sessions
gw --instance '<实例目录>' interrupt exploration
gw --instance '<实例目录>' reset exploration
gw --instance '<实例目录>' close exploration
gw --instance '<实例目录>' env pip install -r requirements.txt
```

Agent 执行代码优先使用 `run -`（也可简写为 `-`），源码通过 UTF-8 标准输入传入，避免经过 Windows `.bat` 参数引号处理。PowerShell 示例：

```powershell
$OutputEncoding = [Text.UTF8Encoding]::new($false)
@'
import java
print(java.type('java.lang.System').getProperty('java.version'))
print('中文、单引号和 "双引号" 都按源码传入')
'@ | gw --instance '<实例目录>' run - arg1
```

`run -` 读取到 EOF 后执行，支持 UTF-8 BOM、多行源码和命名会话；`sys.argv` 为 `['<stdin>', 'arg1']`，回溯文件名为 `<stdin>`。源码占用标准输入，脚本中的 `input()` 收到 EOF；需要交互输入时使用文件或 REPL。CLI 的 stdout/stderr 统一为 UTF-8。`-c CODE` 仍可用于简单代码。

选项放在命令前，脚本后面的参数全部传给脚本。脚本和模块使用调用终端的工作目录；文件执行会把脚本目录放入模块搜索路径。未指定 `--session` 的脚本使用临时解释器，完成或断线即关闭。REPL 默认会话名为 `repl`，退出终端后继续保留。命名会话同一时间执行一个请求。

REPL 支持 Python 多行语句、表达式结果、历史、补全和 `input()`。Ctrl+C 中断当前执行，Ctrl+D 离开 REPL；也可从另一终端发送 `interrupt`。中断无法保证立即终止任意第三方原生函数或 Java 阻塞操作；可关闭或重置会话。

## cwd 初始化与模块复用

`graalweave/workspace/` 是默认的 LLM 工作空间，用于存放 Python 模块、包和任务数据。推荐通过 `import` 复用代码；需要直接执行文件时使用 `script` 或 CLI `run`。

在 `cwd` 放置 `__init__.py`，每个会话首次执行代码、文件、模块或 REPL 输入前运行一次，名称写入同一会话的全局命名空间。例如：

```python
# cwd/__init__.py
from graalweave import client, ticks, events, api
import game_state
from baritone import go
```

将相应模块放在 cwd，后续 MCP 调用即可直接使用：

```json
{"code":"print(client.call(game_state.snapshot))"}
{"code":"go(100, 64, 200)"}
```

可复用逻辑写成模块函数或类，import 一次后反复调用；模块的命令行入口放在 `if __name__ == '__main__':` 中。更新模块后重置会话再导入。

初始化按会话执行，不是每次工具调用或每次游戏启动执行；临时会话每次新建，因而每次初始化。切换 cwd 不会重新初始化；首次没有该文件也不会在后续调用补跑。初始化失败会终止本次请求，不自动重试，修复后用 `reset` 重建会话。只设置 MCP cwd、查询状态或补全不触发初始化。初始化时 `__file__` 指向该文件，`__name__` 为 `__main__`。

## 启动脚本

客户端启动完成时（无需进入世界），Mod 自动创建并扫描实例下的 `graalweave/startup/`，按文件名顺序执行该目录直接包含的 `.py` 文件（`__init__.py` 除外，它在每个启动脚本会话初始化时执行），每次启动只执行一轮。安装器也会创建这个目录。例如：

```text
26.3-Fabric/graalweave/startup/
  10_settings.py
  20_events.py
  helpers/
    __init__.py
```

每个脚本使用单独的常驻会话 `startup/文件名.py`，与 MCP 的 `agent` 会话隔离。脚本依次在工作线程执行，不阻塞游戏线程；前一个脚本结束后才执行下一个，报错会记录日志并继续。不要在顶层写无限循环，持续工作使用事件订阅。`input()` 收到 EOF。工作目录与模块搜索路径固定指向 `startup`，不受 MCP 的 `cwd` 配置影响；辅助模块可放在子包中，子目录不会自动执行。

脚本的 `print()`、异常和后续事件回调输出写入游戏日志（`logs/latest.log`），不会自动发送到聊天栏。启动时尚未进入世界；修改 Mod 设置可直接执行，需要玩家或世界的操作应订阅 `join`：

```python
import java
from graalweave import client, events

Minecraft = java.type('net.minecraft.client.Minecraft')

def on_join():
    print(client.call(lambda: Minecraft.getInstance().player.getName().getString()))

joined = events.subscribe('join', on_join)
```

常驻会话保留变量和事件订阅，退出游戏时统一关闭，也可通过 MCP 的 `close` / `interrupt` 管理。修改脚本后下次启动生效；重命名为 `.py.disabled` 可停用。

## Java API 探索

```python
from graalweave import api

api.mods()                                      # Mod id、名称、版本、路径
api.classes('Minecraft', mod='minecraft', exact=True)
api.inspect('net.minecraft.client.Minecraft', query='getInstance', exact=True)
api.inspect('java.lang.Math', query='abs', kind='method', exact=True)  # 保留全部重载
api.inspect('java.lang.Math', query='abs', exact=True, detail=True)   # 完整元数据
```

查询默认返回精简字典：类条目只有 `name`，成员条目只有 `kind/name/signature`，分页信息始终保留。`detail=True` 返回原有完整字段，包括声明类、参数和来源；`exact=True` 在分页前区分大小写匹配完整名称，类也可用二进制短名。索引按需建立并在会话间共享，扫描阶段不加载类。完整接口见 [API Explorer](docs/api-explorer.md)，示例为 `examples/explore.py`。

## 运行时接入 Java 对象

Python 与 Minecraft、其他 Mod 运行在同一个 JVM 中，可直接获取 Java 对象并调用方法；游戏事件也可触发 Python 回调，无需另开服务连接目标 Mod。

脚本可用 Java 类加载器加载新的适配类，以目标 Mod 的类加载器为父加载器，使适配类能访问 Minecraft 和 Mod 类型，并继承原处理类。随后通过 Java 反射，将 Mod 内部的处理对象字段替换为类型兼容的适配对象，后续经过该字段的调用便转向新对象；其他位置持有的旧引用不变。操作游戏对象时仍须遵守线程要求。

这是通过替换引用重定向调用，不是重定义已有类。GraalPy 提供语言互操作，Java 类加载与反射负责接入；GraalWeave 本身不提供已加载类的重定义，修改已有方法字节码需要额外接入 Java Agent / Instrumentation。

## Python 与游戏线程

Java 字段赋值会先按目标类型转换，再写入；支持普通赋值、`setattr`、增量赋值和本地 `.py` 模块中的赋值。`Setting<Integer>.value = 8` 会写入真正的 Java `Integer`，不会写入 `Long`。整数越界、非整数小数或不匹配的类型抛出 Python `TypeError`，字段保留原值。泛型字段通过继承声明或 `getValueClass()` 等类型访问器解析，无法解析时拒绝赋值。Java `float` 方法参数和字段允许正常的单精度舍入，超出范围时报错。Java 对象仍是原始对象，不需要额外包装或手动类型转换。

这层检查针对源码中的字段赋值；Java 方法内部逻辑、反射写入和没有源码的第三方 Python 字节码仍遵循其自身的类型约束。

```python
import java
from graalweave import client, ticks, events

Minecraft = java.type('net.minecraft.client.Minecraft')
mc = Minecraft.getInstance()
position = client.call(lambda: str(mc.player.position()))
print(position)
ticks.wait(20)
subscription = events.subscribe('join', lambda: print('Joined'))
subscription.close()
```

源码中的 `str(...)` 和 `print(...)`（包括 `builtins.str(...)`、`builtins.print(...)`）会把结果文本中独立的浮点数字段四舍五入到两位小数，不改变原始数值；`print` 直接输出嵌套字典、列表同样适用，本地导入模块也适用。`brief(snapshot, digits=2)` 可显式将快照格式化为紧凑 JSON。坐标也可直接用 `str(mc.player.position())` 读取。

当前版本示例：

- `examples/game_state.py`：当前界面、暂停状态和玩家状态。26.3 用 `mc.gui.screen()` 读取界面（无界面为 `None`）；旧示例的 `mc.screen` 字段不可直接使用。无需截图。
- `examples/smooth_attack.py`：可导入的 `SmoothAttack` 类，逐 tick 平滑转向，确认准星命中、距离、视线和冷却后近战并挥手；不负责寻路。
- `examples/villagers.py`：读取附近村民的职业、头盔和生命值，演示 `getattr(obj, 'is')(...)` 调用 Java 保留字方法。默认只观察；加 `--attack` 后正常近战攻击“图书管理员且戴钻石头盔”的目标。先手持斧并关闭菜单；脚本检查视线、距离和冷却，不使用命令或直接改血，不负责寻路。

`client.call(fn)` 在客户端主线程执行短函数，保留 Java 对象并传播异常；`ticks.wait(n)` 等待客户端 tick，不能在主线程使用。事件支持 `tick`、`join`、`leave`，无参数回调在会话工作线程执行。tick 是客户端循环计数，与服务器游戏时间不同。回调繁忙期间同类事件合并，适合状态观察，不用于逐包记录。异常回调会被注销。长脚本运行时，事件回调排队等待该脚本释放会话工作线程。

游戏状态访问使用 `client.call`；文件、网络和计算放在会话线程。Java API 保持原有的线程要求。会话关闭后旧 Java/Python 引用不再使用；切换世界后应重新取得玩家和世界对象。

包环境位于 `graalweave/python/venv`，源码路径为 `graalweave/python/src`。包管理使用同版 GraalPy，安装包之后重置会话。`env rebuild` 在游戏停止后重建 venv。第三方原生包以 GraalPy Windows 实际支持为准，CPython wheel 不能直接替代。`status` 报告实际 GraalPy 语言版本。

接口只监听 `127.0.0.1`，按设计不使用令牌。脚本被视为可信本地代码，具有文件、网络和 Java 访问能力。协议和 Agent 使用说明见 [docs](docs/)。
