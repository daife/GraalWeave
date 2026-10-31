# Java API Explorer

在游戏内 Python 中使用 `from graalweave import api`。返回普通 Python 字典和列表，可直接打印或用 `json.dumps(..., ensure_ascii=False, indent=2)` 输出。不需要预先安装探索工具。

```python
import java
from graalweave import api, client

api.mods()                                       # 已加载 Mod：id、名称、版本、根路径，包含嵌套 Mod
api.classes('Minecraft', mod='minecraft', exact=True)  # 精确匹配二进制短名
api.classes(package='net.fabricmc.fabric.api')   # 包及其子包
api.classes('Baritone', mod='baritone')

info = api.inspect('net.minecraft.client.Minecraft', query='getInstance', kind='method', exact=True)
print(info)
Minecraft = java.type(info['name'])
api.inspect(Minecraft)                           # 也接受 Java 类型、java.lang.Class 或 Java 对象
api.inspect('java.util.ArrayList', inherited=False)
api.inspect('java.lang.Math', query='abs', kind='method')  # 每个重载单独一项
api.inspect('java.lang.Math', query='abs', exact=True, detail=True)  # 完整元数据
```

`classes(query='', *, mod=None, package=None, public_only=True, offset=0, limit=50, exact=False, detail=False)` 返回按完整类名排序的分页结果。默认子串匹配，不区分大小写；`exact=True` 区分大小写匹配完整类名或二进制短名，在分页前过滤。内部类的短名为 `Outer$Inner`。类名可直接传给 `java.type`。

默认每项只有 `name`，避免重复输出 JAR 路径等元数据；`detail=True` 返回原有的 `name/public/exported/mods/sources`。`mods` 为空表示来自普通依赖或 Java 模块；多个来源定义同名类时合并来源，实际加载仍由游戏类加载器决定。Mod 过滤使用 `mods()` 返回的准确 id，未知 id 报错。

`inspect(name, *, query='', kind=None, inherited=True, offset=0, limit=50, exact=False, detail=False)` 返回公开成员的分页结果。`kind` 可选 `constructor`、`method`、`field`、`class`。`query` 默认按成员名做不区分大小写的子串匹配；`exact=True` 区分大小写匹配名称并保留全部重载，不会匹配前后缀相似的方法。`inherited=False` 只看本类声明的成员。

默认顶层只有 `name/items/total/offset/limit/next_offset`；成员只有 `kind/name/signature`。签名省略 `public`、声明类前缀和直接位于 `java.lang` 的类型前缀，保留参数、泛型、重载、静态及其他修饰符。例如：

```python
{'kind': 'method', 'name': 'abs', 'signature': 'static int abs(int)'}
```

`detail=True` 返回原有完整数据：顶层包括泛型声明、包、模块、父类、接口、enum/record 标志、Mod、来源及字节码 `resource`；成员包括 `kind/name/signature/declaring_class/modifiers`，方法和构造器还有 `parameters/throws/varargs`，方法有 `returns`，字段有 `type`。完整签名不缩写。没有编译进字节码的参数名为 `None`；不猜测参数名、不读取字段值。需要程序化分析参数或判断继承来源时使用此模式。

两种查询都返回 `items`、`total`、`offset`、`limit`、`next_offset`。默认每页 50 项，没有总数截断；最后一页 `next_offset` 为 `None`：

```python
offset = 0
while True:
    page = api.classes(mod='minecraft', offset=offset, limit=100)
    for item in page['items']:
        print(item['name'])
    if page['next_offset'] is None:
        break
    offset = page['next_offset']
```

首次查询按需建立共享索引，扫描 Fabric 提供的全部 Mod 根路径、JVM classpath、URL 类加载器路径及启动模块层中的 Java 模块。支持目录、嵌套 Mod 的独立根路径和多版本 JAR；多版本 JAR 按当前 Java 版本选择字节码。后续查询复用索引。首次扫描可能耗时，应直接在会话线程调用探索接口。`roots` 和 `sources` 使用 URI，能区分各个 JAR 内部的根路径。

发现类只解析字节码，不加载或初始化类；成员查询加载指定类但不主动执行静态初始化，也不调用成员。反射读取的是经过游戏类加载器转换后的实际成员。缺失依赖、类无法加载等错误直接向调用者报告。

默认隐藏非 public 类和 Java 模块未导出的包；这里的可见性来自扫描的原始字节码。Access Widener 等加载期转换可能改变可见性，搜索这类条目时使用 `public_only=False`，再通过 `inspect` 查看实际成员。这不改变 JVM 的访问规则。索引覆盖可扫描的静态类文件，不包含运行时生成且没有类文件的类、自定义加载器的隐藏资源，也不把字节码中的所有 public 实现类认定为 Mod 官方稳定 API。已获得这些对象时仍可尝试 `inspect(object)`。游戏状态读取和 API 调用继续遵守原有线程要求，使用 `client.call`。

REPL 补全使用同一类索引提供完整类名和 `java.type('包名...` 补全；Python 变量、内置名称、关键字和属性链也可补全。不执行函数调用或下标表达式，不再使用 `rlcompleter`，没有原来的 200 项截断。完整参数及重载通过 `inspect` 查看。
