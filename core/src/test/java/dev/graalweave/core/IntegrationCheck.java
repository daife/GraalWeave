package dev.graalweave.core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** One end-to-end check: real GraalPy, real protocol, a simulated client thread. */
public final class IntegrationCheck {
    public static void main(String[] args) throws Exception {
        Path instance = Files.createTempDirectory("GraalWeave 中文 path ");
        PythonEnvironment.initialize(instance);
        Path fixtureJar = instance.resolve("[API 探索] fixture.jar");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(fixtureJar))) {
            for (String suffix : List.of("ExplorerFixture", "HiddenFixture")) {
                String resource = "dev/graalweave/core/IntegrationCheck$" + suffix + ".class";
                jar.putNextEntry(new java.util.jar.JarEntry(resource));
                try (var bytes = IntegrationCheck.class.getClassLoader().getResourceAsStream(resource)) { bytes.transferTo(jar); }
                jar.closeEntry();
            }
        }
        var fixtureFs = FileSystems.newFileSystem(fixtureJar);
        var mainThread = new AtomicReference<Thread>();
        var game = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "test-game"); mainThread.set(thread); return thread;
        });
        var access = new GameAccess() {
            public boolean isMainThread() { return Thread.currentThread() == mainThread.get(); }
            public void execute(Runnable action) { game.execute(action); }
            public ClassLoader classLoader() { return getClass().getClassLoader(); }
            public List<ApiExplorer.Mod> mods() { return List.of(new ApiExplorer.Mod("fixture", "Explorer fixture", "1.0", List.of(fixtureFs.getPath("/")))); }
        };
        try (fixtureFs; var runtime = new RuntimeService(instance, access); var server = new ProtocolServer(runtime)) {
            game.scheduleAtFixedRate(runtime::tick, 0, 10, TimeUnit.MILLISECONDS);
            Path startup = Files.createDirectories(instance.resolve("graalweave/startup"));
            Files.createDirectories(startup.resolve("helpers"));
            Files.writeString(startup.resolve("helpers/__init__.py"), "value = 7\n");
            Files.writeString(startup.resolve("10_setup.py"), """
                    from graalweave import client, ticks, events
                    import helpers
                    assert helpers.value == 7
                    assert client.call(lambda: 42) == 42
                    ticks.wait(2)
                    try:
                        input()
                    except EOFError:
                        pass
                    else:
                        raise AssertionError('startup input must receive EOF')
                    joined = []
                    subscription = events.subscribe('join', lambda: joined.append(True))
                    """);
            Files.writeString(startup.resolve("20_failure.py"), "raise RuntimeError('startup failure fixture')\n");
            Files.writeString(startup.resolve("30_finish.py"), "assert 'joined' not in globals()\nprint('startup continued')\n");
            Files.writeString(startup.resolve("ignored.py.disabled"), "raise AssertionError('disabled script ran')\n");
            var startupDone = new CompletableFuture<Void>();
            var startupResults = new CopyOnWriteArrayList<String>();
            runtime.runStartup(message -> {
                if (Json.string(message, "type", "").equals("done")) {
                    startupResults.add(Json.string(message, "script", "") + ":" + Json.string(message, "status", ""));
                    if (startupResults.size() == 3) startupDone.complete(null);
                }
            });
            startupDone.get(45, TimeUnit.SECONDS);
            if (!startupResults.equals(List.of("10_setup.py:ok", "20_failure.py:error", "30_finish.py:ok")))
                throw new AssertionError(startupResults);
            runtime.event("join");
            check(runtime.find("startup/10_setup.py"), "assert joined == [True]; subscription.close()");
            Path cwd = Files.createDirectories(instance.resolve("工作目录"));
            Files.writeString(cwd.resolve("helpers.py"), "def answer(): return 42\n");
            Files.writeString(cwd.resolve("__init__.py"), "from helpers import answer\ninit_count = globals().get('init_count', 0) + 1\n");
            Session initialized = runtime.session("initialized");
            run(initialized, Json.object("cwd", cwd.toString(), "code", "assert answer() == 42 and init_count == 1; assert '__file__' not in globals()"), "ok");
            Files.writeString(cwd.resolve("__init__.py"), "raise RuntimeError('initializer changed')\n");
            run(initialized, Json.object("cwd", instance.toString(), "code", "assert answer() == 42 and init_count == 1"), "ok");
            runtime.resetSession("initialized");
            run(runtime.find("initialized"), Json.object("cwd", cwd.toString(), "code", "raise AssertionError('request ran after init failure')"), "error");
            check(runtime.find("initialized"), "assert 'answer' not in globals()");
            Session session = runtime.session("integration");
            check(session, "import sys, java; print(sys.version); x = 40; assert java.type('java.lang.Math').abs(-2) == 2");
            check(session, """
                    from graalweave import api
                    assert api.mods()[0]['version'] == '1.0'
                    found = api.classes('ExplorerFixture', mod='fixture', package='dev.graalweave.core', detail=True)
                    assert found['total'] == 1 and found['items'][0]['mods'] == ['fixture']
                    assert api.classes('HiddenFixture', mod='fixture')['total'] == 0
                    assert api.classes('HiddenFixture', mod='fixture', public_only=False)['total'] == 1
                    assert api.classes('java.lang.String')['total'] >= 1
                    assert api.classes('jdk.internal.misc.Unsafe')['total'] == 0
                    page = api.classes(package='java.lang', limit=2)
                    assert len(page['items']) == 2 and page['next_offset'] == 2
                    assert api.classes(package='java.lang', offset=2, limit=2)['items'][0] != page['items'][0]
                    fixture = found['items'][0]['name']
                    assert api.classes('IntegrationCheck$ExplorerFixture', exact=True)['total'] == 1
                    assert api.classes(fixture, exact=True)['items'] == [{'name': fixture}]
                    assert api.classes('ExplorerFixture', exact=True)['total'] == 0
                    assert api.classes(fixture.lower(), exact=True)['total'] == 0
                    details = api.inspect(fixture, query='echo', exact=True, kind='method', inherited=False, limit=1, detail=True)
                    assert details['total'] == 2 and details['next_offset'] == 1
                    assert details['items'][0]['declaring_class'] == fixture
                    assert details['items'][0]['parameters'][0]['type'] in ('int', 'java.lang.String')
                    assert api.inspect(fixture, query='echo', exact=True, offset=1, limit=1)['next_offset'] is None
                    compact = api.inspect(fixture, query='echo', exact=True)
                    assert compact['total'] == 2 and 'sources' not in compact
                    assert {m['signature'] for m in compact['items']} == {'static int echo(int)', 'static String echo(String)'}
                    assert all(set(m) == {'kind', 'name', 'signature'} for m in compact['items'])
                    assert api.inspect(fixture, query='echo')['total'] == 3
                    assert api.inspect(fixture, query='Echo', exact=True)['total'] == 0
                    assert api.inspect(fixture, kind='field', detail=True)['items'][0]['type'] == 'int'
                    assert api.inspect(fixture, kind='constructor')['total'] == 1
                    assert api.inspect(fixture, query='size', inherited=True)['total'] > 0
                    assert api.inspect(fixture, query='size', inherited=False)['total'] == 0
                    assert java.type('java.lang.System').getProperty('graalweave.explorer.initialized') is None
                    assert api.inspect(java.type('java.lang.Math'), query='abs', kind='method')['total'] >= 4
                    assert api.inspect(java.type('java.util.ArrayList')())['name'] == 'java.util.ArrayList'
                    many = type('Many', (), {'method%03d' % i: i for i in range(250)})()
                    from graalweave import brief
                    import json
                    state = {'position': (1.234567890123, -2.987654321), 'alive': True, 'id': 2687}
                    shown = json.loads(brief(state))
                    assert shown == {'position': [1.23, -2.99], 'alive': True, 'id': 2687}
                    assert state['position'][0] == 1.234567890123
                    assert json.loads(brief(java.type('java.lang.StringBuilder')('long contents'))) == 'StringBuilder'
                    assert json.loads(brief(java.type('java.lang.Math'))) == 'Math'
                    assert brief(1.23456, digits=3) == '1.235'
                    import builtins
                    assert str('(46.35004316448207, 80.0, -181.68040637672522)') == '(46.35, 80.0, -181.68)'
                    assert str(java.type('java.lang.StringBuilder')('(46.35004316448207, 80.0)')) == '(46.35, 80.0)'
                    assert builtins.str(1.234567) == '1.23'
                    assert str(b'hello', encoding='utf-8') == 'hello'
                    assert isinstance('hello', str) and type('hello') is str
                    assert str('minecraft:item123 2687 v1.2.3') == 'minecraft:item123 2687 v1.2.3'
                    exec("converted = str(1.234567)")
                    assert converted == '1.23'
                    def shadowed(str):
                        return str(1.234567)
                    assert shadowed(lambda value: value) == 1.234567
                    import io
                    output = io.StringIO()
                    report = {'repairs': [{'pos': [191.50999999046326, 70.0, -255.51000000953672], 'verified': True}]}
                    print('entities', report, sep=': ', end='!', file=output, flush=True)
                    assert output.getvalue() == "entities: {'repairs': [{'pos': [191.51, 70.0, -255.51], 'verified': True}]}!"
                    assert report['repairs'][0]['pos'][0] == 191.50999999046326
                    builtins.print(1.234567, file=output)
                    assert output.getvalue().endswith('1.23' + chr(10))
                    def custom_print(print):
                        return print(report)
                    assert custom_print(lambda value: value) is report
                    assert state['position'][0] == 1.234567890123
                    """);
            if (session.complete("many.method").get(10, TimeUnit.SECONDS).size() != 250) throw new AssertionError("completion truncated");
            if (!session.complete("java.type('java.lang.Str").get(10, TimeUnit.SECONDS).contains("java.type('java.lang.String')")) throw new AssertionError("class completion");
            check(session, "assert x == 40; from graalweave import client, ticks, events; assert client.call(lambda: 42) == 42; ticks.wait(2)");
            check(session, """
                    Fixture = java.type('dev.graalweave.core.IntegrationCheck$TypedFixture')
                    typed = Fixture()
                    typed.setting.value = 8
                    assert typed.readInteger() == 8
                    typed.setting.value += 1
                    setattr(typed.setting, 'value', 10)
                    assert client.call(lambda: typed.readInteger()) == 10
                    typed.number, typed.setting.value = 3, 11
                    Fixture.staticNumber = 5
                    assert Fixture.staticNumber == 5
                    typed.decimal = 12.3456789
                    typed.setDecimal(23.456789)
                    assert abs(typed.decimal - 23.456789) < 0.00001
                    typed.inherited.value = 12
                    typed.integers.values = [1, 2, 3]
                    assert typed.integers.sum() == 6
                    for bad in (2**40, 1.5, 'eight', True):
                        try:
                            typed.setting.value = bad
                        except TypeError:
                            pass
                        else:
                            raise AssertionError('invalid assignment accepted: ' + str(bad))
                        assert typed.readInteger() == 11
                    exec('typed.setting.value = 13')
                    assert typed.readInteger() == 13
                    try:
                        typed.unknown.value = 7
                    except TypeError as exc:
                        assert 'generic type' in str(exc)
                    else:
                        raise AssertionError('unresolved generic must fail before writing')
                    try:
                        typed.decimal = 1e100
                    except TypeError:
                        pass
                    else:
                        raise AssertionError('float overflow accepted')
                    class Plain:
                        pass
                    plain = Plain()
                    plain.value = 3
                    plain.value += 1
                    assert plain.value == 4
                    """);
            check(session, "try:\n    client.call(lambda: 1/0)\nexcept Exception:\n    pass\nelse:\n    raise AssertionError('exception lost')");
            check(session, "hits = []; sub = events.subscribe('tick', lambda: hits.append(1))");
            Thread.sleep(80);
            check(session, "assert hits; sub.close()");
            check(session, "import importlib.metadata; assert importlib.metadata.version('pip')");
            Path script = instance.resolve("参数 script.py");
            Files.writeString(script, "import sys, sibling\nassert sibling.value == 7\nassert sys.argv[1:] == ['中文']\nassert __file__.endswith('参数 script.py')\n");
            Files.writeString(instance.resolve("sibling.py"), "value = 7\nimport java\nf = java.type('dev.graalweave.core.IntegrationCheck$TypedFixture')()\nf.setting.value = 9\nassert f.readInteger() == 9\n");
            run(session, Json.object("mode", "file", "filename", script.toString(), "args", List.of("中文")), "ok");
            run(session, Json.object("mode", "repl", "code", "for i in range(2):"), "incomplete");
            run(session, Json.object("mode", "repl", "code", "for i in range(2):\n    pass\n"), "ok");
            if (session.complete("cli").get(10, TimeUnit.SECONDS).stream().noneMatch(s -> s.startsWith("client"))) throw new AssertionError("completion");
            var done = new CompletableFuture<JsonObject>();
            session.submit(Json.object("code", "while True: pass"), message -> {
                if (Json.string(message, "type", "").equals("done")) done.complete(message);
            });
            Thread.sleep(300);
            session.interrupt();
            if (!done.get(10, TimeUnit.SECONDS).get("status").getAsString().equals("interrupted")) throw new AssertionError("interrupt");
            check(session, "assert x == 40");
            System.out.println("PASS: startup scripts, environment, API Explorer, Java interop, main thread, ticks/events, file/argv, REPL, completion, interrupt/reuse");
        } finally { game.shutdownNow(); }
        System.out.println("Integration environment: " + instance);
    }
    public static class ExplorerFixture extends ArrayList<String> {
        static { System.setProperty("graalweave.explorer.initialized", "true"); }
        public int value;
        public static int echo(int value) { return value; }
        public static String echo(String value) { return value; }
        public static void echoExtra() {}
    }
    private static class HiddenFixture {}
    public static class Holder<T> { public T value; }
    public static class IntegerHolder extends Holder<Integer> {}
    public static class ReifiedHolder<T> extends Holder<T> {
        private final Class<T> type;
        public ReifiedHolder(Class<T> type) { this.type = type; }
        public Class<T> getValueClass() { return type; }
    }
    public static class IntegerList extends ListHolder<Integer> {
        public int sum() { return values.stream().mapToInt(Integer::intValue).sum(); }
    }
    public static class ListHolder<T> { public List<T> values; }
    public static class TypedFixture {
        public final ReifiedHolder<Integer> setting = new ReifiedHolder<>(Integer.class);
        public final IntegerHolder inherited = new IntegerHolder();
        public final Holder<Object> unknown = new Holder<>();
        public final IntegerList integers = new IntegerList();
        public int number;
        public static int staticNumber;
        public float decimal;
        public void setDecimal(float value) { decimal = value; }
        public int readInteger() { return setting.value; }
    }
    private static void check(Session session, String code) throws Exception { run(session, Json.object("code", code), "ok"); }
    private static void run(Session session, JsonObject request, String expected) throws Exception {
        var done = new CompletableFuture<JsonObject>();
        session.submit(request, message -> {
            String type = Json.string(message, "type", "");
            if (type.equals("output")) System.out.print(Json.string(message, "text", ""));
            if (type.equals("done") || type.equals("error")) done.complete(message);
        });
        JsonObject result = done.get(45, TimeUnit.SECONDS);
        if (!Json.string(result, "status", "").equals(expected)) throw new AssertionError(result);
    }
}
