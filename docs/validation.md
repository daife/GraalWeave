# 验证记录

## 2026-10-30：cwd 初始化与模块复用

真实 GraalPy 核心检查通过：cwd 相邻模块导入、初始化名称保留、每会话只运行一次、切换 cwd 不重跑、reset 重新初始化、初始化失败终止请求且不自动重试。`gradlew.bat :core:integration :cli:integration offlineZip` 全部通过。本次未重启 Minecraft 实机验证。

以下为 2026-10-29 Windows x64 的历史验证记录。

## 自动启动脚本

`gradlew.bat :core:integration offlineZip` 通过。新增集成检查使用真实 GraalPy 验证启动脚本按文件名顺序执行、独立常驻会话、相邻子包导入、主线程调用、tick 等待、stdin EOF、失败后继续、停用文件跳过，以及脚本结束后 `join` 事件订阅仍可运行。Fabric 启动事件接线已编译通过；尚未重启实际 Minecraft 实例验证自动启动。

## 自动类型转换与内置 MCP

通过真实 GraalPy 验证 Java 整数泛型字段写入、继承泛型解析、列表元素转换、静态字段、`setattr`、增量和解包赋值、动态 `exec`、导入脚本，以及普通 Python 属性行为。浮点参数允许单精度舍入；整数越界、非整数小数、错误类型、浮点越界和无法解析的泛型在写入前报错，并保持字段原值。原有 REPL 多行、回溯、跨线程调用与中断复用检查继续保留。

CLI 子进程通过实际 stdio MCP 和游戏连接验证单工具发现、UTF-8 多行代码、中文脚本路径、参数、默认会话保留、`input()` EOF、脚本失败、参数冲突、运行期间 ping、取消及取消后的会话复用。MCP 不依赖 PowerShell；安装器生成直接启动 Java 的配置，并支持 `-Codex` 注册。

这些检查使用模拟游戏线程，尚未对这次修改后的 Mod 做 Minecraft 实机试玩。

## Agent 接口精简

`gradlew.bat :core:integration`、`gradlew.bat :cli:integration`、`gradlew.bat offlineZip` 通过。

核心检查使用真实 GraalPy 验证精简/完整查询、精确匹配、重载与分页、类扫描不初始化，以及 `brief` 的浮点数展示、原数据保留和 Java 对象短类名。CLI 检查通过独立子进程、实际协议与解释器验证 UTF-8/BOM、多行源码、中文、引号、反斜线、argv、命名会话、stdin EOF、退出码及 `<stdin>` 回溯；不创建交互终端。CLI stdout/stderr 固定 UTF-8，避免 Windows 默认编码损坏中文回溯。

使用新构建的 `.bat`，通过 PowerShell UTF-8 here-string 管道连接当前 26.3 游戏，成功传入包含中文、引号及反斜线的源码。在临时解释器中加载工作区新版 Python 模块并运行 `game_state.py`、`villagers.py` 观察模式：准确读取 `PauseScreen`、暂停状态、玩家坐标、村民职业与头盔，浮点数按两位小数输出。此验证未替换运行中游戏的 JAR，未截图。

此前同次实机试玩通过 `MultiPlayerGameMode.attack` 正常斧击，击杀两名戴钻石头盔的图书管理员；四名不同职业/头盔的对照村民保持 20 血，服务端确认两名目标移除。攻击检查距离、视线和冷却，未使用伤害/击杀命令或直接修改生命值。新增 `villagers.py --attack` 示例采用相同调用方式。

## API Explorer 增补验证

`gradlew.bat :core:integration offlineZip` 通过。通过真实 GraalPy 验证 Mod 元数据、JAR 文件系统根路径、Mod/包过滤、非公开类和未导出模块包过滤、JDK 类发现、分页、完整重载与参数类型、继承成员、Java 类型和对象查询。带静态初始化标记的类在搜索和成员查询后未初始化。250 项属性补全全部保留，类名补全可返回直接使用的 `java.type(...)` 表达式。

此增补使用模拟游戏线程验证，未重新启动 Minecraft 实例。

## 生存模式试玩

2026-10-29，在实际 26.3 Fabric 实例新建 `GraalWeave Survival Playtest`，生存模式、普通难度、作弊关闭、初始背包为空。进入世界后全部通过 GraalWeave 脚本操作，没有使用 computer-use。

通过 Baritone 采集 6 个白桦原木；使用正常容器交互合成木板、工作台、木棍、箱子和木斧。工作台放在 `(-5, 70, 1)`，箱子放在 `(-6, 70, 2)`。木斧存入箱子第一个槽位，关闭并重新打开箱子确认留存，玩家背包不再持有木斧。结束时生命值为 20，世界已保存退出。

试玩发现 ZIP 文件系统返回的 JAR URI 中方括号没有转义，导致 API Explorer 扫描带此类名称的 Mod 时失败；已修正嵌套文件 URI 的构造方式，集成检查的 JAR 文件名加入中文、空格和方括号覆盖该问题。

## 构建

Java 25 / Oracle GraalVM 25.4.4.1.1，Gradle 9.8.2，Loom 1.19.3。

`gradlew.bat offlineZip :core:integration`：通过。

GraalPy 实际报告 Python 3.13.14。离线环境使用随依赖发布的 Windows 原生启动器和 ensurepip；初始化设置 PIP_NO_INDEX 与禁用远程补丁库，不在初始化过程中安装远程包。

## 核心集成检查

通过真实 GraalPy 与模拟游戏线程检查：Java 对象访问、跨线程 Python 回调与异常、tick 等待、事件订阅、文件参数和相邻模块导入、REPL 多行判定与补全、无限循环中断及解释器复用。环境和测试脚本位于包含中文及空格的临时路径。

## Minecraft 实例

在 `<实例目录>` 安装实际发布 JAR 与环境，使用 Java 25 启动 Minecraft 26.3 / Fabric Loader 0.20.1，保留原有 Mod。验证世界单独创建为 `GraalWeave Validation`。

已通过：

- 通过 TCP 和配套 CLI 执行游戏 JVM 内的 Python，取得原始 Minecraft 类和对象。
- 从客户端主线程读取玩家坐标，读取 Fabric Mod 列表，取得 Baritone provider。
- 执行 Baritone 示例提交目标，并通过公开 API 取消路径任务。
- tick 等待、进入世界事件、命名会话状态保留和临时会话隔离。
- CLI 标准输入；REPL 表达式、多行代码和退出。
- 通过包管理命令安装 colorama 0.4.6，并在游戏内导入。
- 协议模块执行及 argv、原始脚本文件名和行号回溯。
- 无限循环和长时间 tick 等待均可通过另一连接中断；执行期间其他会话可调用游戏主线程，中断后原会话可复用。
- 伪终端 Ctrl+C 能取消正在执行的命名会话。针对被识别为 DumbTerminal 的终端也注册 JLine 的原生 INT 处理。

原生扩展按 GraalPy 的平台兼容性使用。colorama 在 Windows 导入时触发了 GraalPy 的 ctypes 支持；不据此推断所有第三方 C 扩展可用。

## 退出记录

GraalWeave 的 connection.json 已删除，线程转储中没有遗留的 GraalWeave 工作线程。整套实例在退出时触发了 Minecraft 的 post-main shutdown watchdog；剩余非 daemon 工作线程的堆栈位于 Baritone。该现象属于当前整套实例的退出限制，本版未修改其他 Mod。原始报告保留在本机实例的 `crash-reports/`，不随项目分发。

验证结束后清理独立测试世界、临时环境和仅用于验证的 colorama 包；正常的 Minecraft 配置及 Mod 保留。
