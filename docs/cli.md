# 终端使用

CLI 位于 `<实例目录>/graalweave/client/bin/graalweave.bat`。在 PowerShell 中可先定义一个快捷函数，以下命令即可直接使用：

```powershell
function gw { & '<实例目录>\graalweave\client\bin\graalweave.bat' @args }
```

将函数中的 `<实例目录>` 替换为实际路径。终端入口会自动选择它所在的实例，启动游戏后运行：

```powershell
gw status
gw run '.\examples\explore.py' arg1
gw -m my_package arg1
gw --session exploration repl
gw --session exploration run '.\inspect.py'
gw sessions
gw interrupt exploration
gw reset exploration
gw close exploration
gw env pip install -r requirements.txt
```

Agent 执行代码优先使用 `run -`（也可简写为 `-`），源码通过 UTF-8 标准输入传入，避免经过 Windows `.bat` 参数引号处理。PowerShell 示例：

```powershell
$OutputEncoding = [Text.UTF8Encoding]::new($false)
@'
import java
print(java.type('java.lang.System').getProperty('java.version'))
print('中文、单引号和 "双引号" 都按源码传入')
'@ | gw run - arg1
```

`run -` 读取到 EOF 后执行，支持 UTF-8 BOM、多行源码和命名会话；`sys.argv` 为 `['<stdin>', 'arg1']`，回溯文件名为 `<stdin>`。源码占用标准输入，脚本中的 `input()` 收到 EOF；需要交互输入时使用文件或 REPL。CLI 的 stdout/stderr 统一为 UTF-8。`-c CODE` 仍可用于简单代码。

选项放在命令前，脚本后面的参数全部传给脚本。脚本和模块使用调用终端的工作目录；文件执行会把脚本目录放入模块搜索路径。未指定 `--session` 的脚本使用临时解释器，完成或断线即关闭。REPL 默认会话名为 `repl`，退出终端后继续保留。命名会话同一时间执行一个请求。

REPL 支持 Python 多行语句、表达式结果、历史、补全和 `input()`。Ctrl+C 中断当前执行，Ctrl+D 离开 REPL；也可从另一终端发送 `interrupt`。中断无法保证立即终止任意第三方原生函数或 Java 阻塞操作；可关闭或重置会话。
