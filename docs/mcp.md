# 内置 MCP

将模组 JAR 放入实例的 `mods` 并启动游戏。模组会自动初始化 GraalPy 环境、释放 CLI，并在每次启动时生成或更新 `<实例目录>/graalweave/mcp.json`。其中 `mcpServers.graalweave` 的 `command` 和 `args` 已填写游戏所用 Java 与实例的实际绝对路径。

将该文件的配置复制到支持 stdio MCP 的 Agent 工具中，或使用工具的配置导入功能。已有 `mcpServers` 时合并其中的 `graalweave` 条目；不同格式按对应工具要求填写相同的 `command` 和 `args`，传输选择 stdio。无需服务器 URL。模组不会自动注册或修改任何 Agent 工具的配置。

保存后让 Agent 工具重新加载 MCP，确认出现 `graalweave` 工具，再调用 `{"action":"status"}` 检查游戏连接。更换实例位置或游戏 Java 路径后，启动游戏并重新复制生成的配置。

服务器随 CLI 打包，通过 stdio JSON-RPC 提供一个 `graalweave` 工具。它复用游戏的本机连接，游戏未启动时仍可发现工具，执行会返回明确的连接错误。初始化和工具发现不加载 Python 解释器。标准输出只包含 MCP 消息，游戏输出作为工具结果返回。

最小输入：

```json
{"code":"print('你好，Minecraft')"}
{"script":"task.py"}
```

可复用逻辑优先通过 `code` 导入一次，后续直接调用函数，例如 `import game_state`，然后 `print(client.call(game_state.snapshot))`。模块放在 cwd；命令行入口用 `if __name__ == '__main__':` 保护。一次性文件执行用 `script`。

`cwd/__init__.py` 在每个会话首次执行前运行一次，导入和变量直接进入会话全局命名空间。切换 cwd 不重新执行；修改后用 `reset`。初始化报错终止本次请求，不自动重试。只设置 cwd 不执行初始化。完整语义见 [cwd 初始化](scripting.md#cwd-初始化与模块复用)。

可选字段：

| 字段 | 含义 |
|---|---|
| `session` | 默认 `agent`，连续调用保留变量和 Java 引用 |
| `args` | 传给脚本的字符串参数数组 |
| `cwd` | 设置并持久保存工作目录，可单独传入；省略时沿用。`code` 从这里导入模块，`script` 路径相对于这里解析，模块从脚本自身目录导入 |
| `action` | 默认 `run`，也可用 `status`、`sessions`、`interrupt`、`close`、`reset` |

`code` 与 `script` 不能同时提供；只传 `cwd` 时仅设置目录，不执行 Python，也不需要游戏连接。执行结果直接以 MCP 文本内容返回 stdout，不再套 JSON 字符串；stderr 作为独立文本块并标注 `stderr:`，错误消息和非零退出码单独返回。Windows 换行统一为 LF；无输出时 `content` 为空数组；脚本报错、无效参数和连接失败返回 `isError: true`。`SystemExit` 的非零退出码也视为失败。`input()` 收到 EOF。

MCP 读取线程与执行分离，可在执行期间处理状态查询和中断。取消请求会中断对应会话；正常脚本执行结束后，命名会话仍可继续使用。任务结束用 `{"action":"close"}` 清理会话及订阅。

首次启动默认使用实例下的 `graalweave/workspace`。MCP 每次启动只读取一次 `graalweave/mcp-settings.json`；文件不存在时生成 `{"cwd":"workspace"}`，其中相对路径以配置文件所在目录为基准。传入 `cwd` 会立即更新内存并把绝对路径保存到该文件，后续调用与下次启动均沿用。手动修改文件需要重启 MCP 才会读取。

`cwd` 与 `code` / `script` 并列，建议使用绝对路径；调用中的相对 `cwd` 以当前工作目录为基准。目录不存在时自动创建：

```json
{"cwd":"<工作目录绝对路径>"}
{"code":"from my_module import Task\nTask().run()"}
{"script":"task.py"}
```

工具描述用英文展示本次 MCP 启动时的目录、默认目录、配置路径和启动脚本的绝对路径，运行期间不随 `cwd` 改动，重启后更新。启动脚本固定放在实例下的 `graalweave/startup`，与 MCP `cwd` 和终端 `pwd` 无关。

工具描述还直接提供核心用法，无需读取仓库：

```python
from graalweave import api, client, ticks, events
import java

print(api.mods())
print(api.classes('Minecraft', mod='minecraft', exact=True))
print(api.inspect('net.minecraft.client.Minecraft', query='getInstance', kind='method', exact=True))
mc = java.type('net.minecraft.client.Minecraft').getInstance()
print(client.call(lambda: mc.player.getHealth()))  # 先进入世界
```

`classes` / `inspect` 用 `offset=next_offset` 翻页，`detail=True` 展示完整元数据。游戏对象通过短小的 `client.call` 访问，`ticks.wait` 放在回调外。事件用 `events.subscribe(name, callback)`，名称支持 `join`、`leave`、`tick`；回调在当前脚本结束后由会话工作线程处理，结束时关闭订阅。启动脚本目录与生命周期见 [脚本指南](scripting.md#启动脚本)。

较长操作可以调整 Agent 工具的调用超时，或拆成几次调用；同一会话一次只执行一个请求。

传输与工具结果遵循 [MCP stdio](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) 和 [Tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools)。
