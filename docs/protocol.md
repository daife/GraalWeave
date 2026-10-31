# 协议 v1

读取实例下 `graalweave/connection.json`，连接其中的本机端口。该文件包含 `protocol`、`host`、`port`、`pid`、`instance`；正常退出删除，异常退出后可能残留，以实际连接结果为准。同一实例目录只允许一个 GraalWeave 监听器。

TCP 双向 UTF-8 JSON Lines，一行一个 JSON 对象。客户端为请求设置唯一字符串 `id`，服务端在响应中回传。执行响应另外携带 `execution` UUID；输出和输入事件携带会话名与正在执行的 execution。

| op | 请求字段 | 响应 |
|---|---|---|
| status | 无 | result.data：版本、实例、进程、会话 |
| sessions | 无 | result.data：会话列表 |
| execute | mode、可选 session、cwd、args | started，若干事件，最后 done |
| complete | session、text | result.data：补全字符串数组 |
| input | session、text（null 表示 EOF） | result |
| interrupt | session | result 或 error |
| close / reset | session | result |

execute 的 mode 为 `file`（filename 为游戏所在机器的文件路径）、`module`（module 为模块名）、`code` 或 `repl`（code 为源码，filename 可选）。省略 session 创建临时会话；省略 cwd 使用该会话当前目录。args 是传给脚本的字符串数组。每个会话首次 execute 在执行请求前运行 cwd 下的 `__init__.py`（若存在），共享会话全局名称；后续切换 cwd 不重跑。初始化失败终止该请求，不自动重试；reset 后重新初始化。

CLI 的 `run -`（或 `-`）将完整 UTF-8 stdin 作为 `mode=code`、`filename=<stdin>` 提交，去掉可选 UTF-8 BOM；不新增协议操作。此模式读取源码后不创建交互终端，收到 `input` 事件即回复 `text=null`。`sys.argv[0]` 为 `<stdin>`。

```json
{"id":"1","op":"execute","session":"agent","mode":"code","code":"x = 42; print(x)"}
{"id":"1","type":"started","execution":"...","session":"agent"}
{"type":"output","session":"agent","execution":"...","stream":"stdout","text":"42\n"}
{"id":"1","type":"done","execution":"...","status":"ok"}
```

done.status 为 `ok`、`error`、`interrupted`、`exit` 或 `incomplete`；exit 带 exitCode。Python 异常回溯通过 stderr 输出，初始化或宿主错误可附 message。REPL 每次提交完整累计文本，incomplete 后追加下一行并重新提交。Java 对象不序列化，经命名会话变量保留。

input 事件表示解释器需要一行标准输入；另发 input 请求。interrupt 应使用独立连接，避免排在执行输出之后。关闭提交执行的连接会终止临时会话；命名会话继续存在，等待中的标准输入收到 EOF。断开后输出不缓存；重新执行该会话会重新绑定输出连接。

事件回调失败发送 subscription_error（subscription、message），并自动注销。可通过会话保存 Subscription 对象来主动关闭。
