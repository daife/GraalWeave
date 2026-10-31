# Agent 工作方式

优先调用 MCP 的 `graalweave` 工具：`{"code":"..."}` 直接执行源码，`{"script":"task.py","args":[]}` 运行脚本，`{"action":"status"}` 检查连接。默认会话是 `agent`，不需要 PowerShell、here-string 或编码设置。其他会话用 `session`；需要继续复用时保留会话；任务结束后 `{"action":"close"}`。没有 MCP 时才使用下列 CLI 工作方式。

默认工作空间为实例下的 `graalweave/workspace/`，用于存放模块、包和任务数据；优先通过 `import` 复用代码。

Java 数值参数和字段按目标类型转换。字段赋值在写入前校验，包括泛型 `Setting<Integer>.value`；无法转换或无法解析泛型时抛出 `TypeError`，不修改字段。无需自行给 Python 整数套 Java 包装类型。Java 对象仍可直接交给其他 API。

1. 用 `status` 确认游戏运行、实例正确和 Python 版本。
2. 用 `--session <任务名>` 建立持续上下文；执行源码优先通过 UTF-8 stdin 传给 `run -`，避免 Windows `.bat -c` 的引号处理。PowerShell 先设 `$OutputEncoding = [Text.UTF8Encoding]::new($false)`，再用单引号 here-string 管道传源码（见 README）。`run -` 的 `input()` 收到 EOF；交互用文件或 REPL。
3. `from graalweave import api`，用 `api.mods()` 查看准确的 Mod id 与版本；用 `api.classes('关键词', mod='id')` 或 `package='包名'` 找类。名称已知时加 `exact=True`；例如 `api.inspect('完整类名', query='成员名', kind='method', exact=True)`。默认输出精简；需要参数元数据、声明来源等才加 `detail=True`。通过 `next_offset` 翻页到 `None`。详见 [API Explorer](api-explorer.md)。
4. `java.type('包名.类名')` 直接访问 Java；游戏对象访问封装到短小的 `client.call` 中。探索使用二进制类名，内部类写为 `Outer$Inner`。
5. 可复用代码写成模块，import 一次后用 MCP `code`、REPL 或后续 `run -` 直接调用函数；一次性执行才用 `run FILE`。cwd 的 `__init__.py` 在会话首次执行前运行一次，可预先导入常用函数；切换目录不重跑，修改后 reset。通过 `print(client.call(snapshot))` 查看状态；位置可用 `str(p.position())`，职业和物品显式读取 ID。
6. 长任务使用 `ticks.wait` 等待，另一个连接可以发送 interrupt。
7. 任务完成后用 close 清理命名会话及其订阅；重置使用 reset。

基础设施不提供路径规划、移动、采集等高层操作。可直接调用已安装 Mod 的公开 API，例如 examples/baritone.py。未安装的 Mod 类会正常抛出类查找异常。

示例脚本：`game_state.py` 读取 `mc.gui.screen()`、暂停及玩家状态；26.3 不使用旧的 `mc.screen` 字段，不主动截图。`villagers.py` 读取职业和装备，并演示 Java 的 `is()` 应写成 `getattr(obj, 'is')(...)`，而不是 `is_()`。默认观察；加 `--attack` 后调用正常玩家攻击接口，只攻击可见、在正常距离内且符合条件的村民，等待攻击冷却。先手持斧并关闭菜单，不负责追踪寻路。

其他示例：`java_access.py` 读取玩家和 Fabric 模组；`ticks.py` 等待 tick、订阅事件；`baritone.py` 提交目标坐标。涉及玩家的示例先进入世界。
