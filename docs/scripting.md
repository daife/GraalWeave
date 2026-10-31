# Python 脚本指南

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

客户端启动完成时（无需进入世界），Mod 自动创建并扫描实例下的 `graalweave/startup/`，按文件名顺序执行该目录直接包含的 `.py` 文件（`__init__.py` 除外，它在每个启动脚本会话初始化时执行），每次启动只执行一轮。例如：

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

接口只监听 `127.0.0.1`，按设计不使用令牌。脚本被视为可信本地代码，具有文件、网络和 Java 访问能力。协议见 [本机协议](protocol.md)，Agent 编程约定见 [Agent 工作方式](agent.md)。

## 运行时调用重定向与类 Mixin 效果

GraalPy 的 Java 互操作让 Python 与 Minecraft、其他 Mod 在同一个 JVM 中直接交换对象并调用方法。除了读取状态和调用现成 API，还可结合 Java 类加载与反射，在游戏运行中重定向特定调用路径。

例如，某个模组通过 `manager.handler.handle(...)` 处理任务：脚本可用 Java 类加载器加载自定义适配类，以目标 Mod 的类加载器为父加载器，并在原类型允许继承时扩展处理类，或实现对应接口。随后将 `manager.handler` 替换为类型兼容的适配对象；公开可写字段可以直接赋值，内部字段可在 Java 访问规则允许时通过反射替换。后续经过该字段的调用就会进入新逻辑，可在调用原处理逻辑前后追加行为，也可替换其处理结果。

这种方式能达到类似 Mixin 的部分拦截、追加或替换行为的效果，适合在不重启游戏的情况下迭代适配逻辑。它依赖目标代码存在可替换的对象引用；其他位置持有的旧引用不会随之更新，静态方法调用或原方法内部的任意指令也不在这一替换范围内。操作游戏对象时仍须遵守线程要求。

GraalWeave 提供的是 GraalPy 互操作环境，适配类及替换逻辑由脚本作者提供；项目没有内置 Mixin 注入或已加载类重定义接口。需要直接修改已有方法字节码时，仍需使用 Mixin 或额外接入 Java Agent / Instrumentation 等机制。
