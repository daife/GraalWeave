"""GraalWeave scheduling primitives. Java classes remain available through java.type."""
import codeop as _codeop
import builtins as _builtins
import json as _json
import keyword as _keyword
import os as _os
import re as _re
import runpy as _runpy
import sys as _sys
import traceback as _traceback
import types as _types
import polyglot as _polyglot
import java as _java

_bridge = None
_main = _types.ModuleType('__main__')
_compiler = _codeop.CommandCompiler()
_initialized = False


def _attach(bridge):
    global _bridge
    _bridge = bridge
    _sys.modules['__main__'] = _main
    from . import _interop
    _interop.install(bridge)


class client:
    @staticmethod
    def call(fn):
        # Waiting Java interop must release the GIL so the game thread can enter Python.
        with _polyglot.gil_locked_during_interop(False):
            return _bridge.call(fn)


class ticks:
    @staticmethod
    def wait(n=1):
        with _polyglot.gil_locked_during_interop(False):
            _bridge.waitTicks(n)


class Subscription:
    def __init__(self, identifier):
        self.id = identifier

    def close(self):
        _bridge.unsubscribe(self.id)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


class events:
    @staticmethod
    def subscribe(name, callback):
        return Subscription(_bridge.subscribe(name, callback))


class api:
    """Discover Java APIs. Queries return Python dicts; class names use Java binary notation (Outer$Inner)."""

    @staticmethod
    def mods():
        """List loaded mods with id, name, version and roots, including nested mods."""
        return _json.loads(_bridge.mods())

    @staticmethod
    def classes(query='', *, mod=None, package=None, public_only=True, offset=0, limit=50,
                exact=False, detail=False):
        """Search class names (case-insensitive substring), optionally within a mod or package tree.

        Returns items, total, offset, limit and next_offset (None on the last page).
        public_only=False also includes non-public classes and unexported module packages.
        exact=True matches a full or binary simple name, case-sensitively, before pagination.
        detail=True includes visibility, mods and sources; otherwise items contain only name.
        Discovery reads bytecode without loading classes. The index is shared across sessions.
        """
        with _polyglot.gil_locked_during_interop(False):
            result = _json.loads(_bridge.classes(query, mod, package, public_only, offset, limit, exact))
        if not detail:
            result['items'] = [{'name': item['name']} for item in result['items']]
        return result

    @staticmethod
    def inspect(name, *, query='', kind=None, inherited=True, offset=0, limit=50,
                exact=False, detail=False):
        """Inspect a class name, Java type or object: hierarchy and a page of public members.

        kind: constructor, method, field, class, or None for all. query filters member names.
        exact=True matches the member name case-sensitively, retaining all overloads.
        Compact items contain kind, name and signature. detail=True includes full metadata.
        inherited=False restricts the results to members declared by this class.
        """
        if not isinstance(name, str):
            if _java.is_type(name):
                name = getattr(name, 'class').getName()
            elif _java.instanceof(name, _java.type('java.lang.Class')):
                name = name.getName()
            else:
                name = name.getClass().getName()
        with _polyglot.gil_locked_during_interop(False):
            result = _json.loads(_bridge.inspect(name, query, kind, inherited, offset, limit, exact))
        if detail:
            return result
        items = []
        for item in result['items']:
            signature = item['signature'].removeprefix('public ')
            owner = item['declaring_class'] + '.'
            token = item['name'] if item['kind'] in ('constructor', 'class') else owner + item['name']
            signature = signature.replace(token, item['name'].rsplit('.', 1)[-1], 1)
            signature = _re.sub(r'\bjava\.lang\.([A-Z]\w*)', r'\1', signature)
            items.append({'kind': item['kind'], 'name': item['name'], 'signature': signature})
        return {key: (items if key == 'items' else result[key])
                for key in ('name', 'items', 'total', 'offset', 'limit', 'next_offset')}


def brief(value, *, digits=2):
    """Format a state snapshot as compact JSON without changing the original values.

    Floats are rounded for display. Unconverted Java objects show only their simple class
    name; extract positions, registry identifiers, item IDs, etc. explicitly in client.call.
    """
    def convert(item):
        if item is None or isinstance(item, (bool, str, int)):
            return item
        if isinstance(item, float):
            return round(item, digits)
        if isinstance(item, dict):
            return {key: convert(val) for key, val in item.items()}
        if isinstance(item, (list, tuple)):
            return [convert(val) for val in item]
        if _java.is_type(item):
            return getattr(item, 'class').getSimpleName()
        return item.getClass().getSimpleName() if _java.is_object(item) else type(item).__name__
    return _json.dumps(convert(value), ensure_ascii=False, separators=(',', ':'))


def _execute(request_json):
    global _initialized
    request = _json.loads(request_json)
    ns = _main.__dict__
    mode = request.get('mode', 'code')
    filename = request.get('filename', '<agent>')
    _os.chdir(request.get('cwd', _os.getcwd()))
    args = request.get('args', [])
    _sys.argv = [filename if mode == 'file' or filename == '<stdin>' else '-c'] + args
    ns.update(__name__='__main__', __package__=None, __spec__=None)
    try:
        if not _initialized:
            _initialized = True
            _sys.path[0:1] = [_os.getcwd()]
            initializer = _os.path.abspath('__init__.py')
            if _os.path.isfile(initializer):
                ns['__file__'] = initializer
                try:
                    with open(initializer, 'rb') as script:
                        exec(compile(script.read(), initializer, 'exec'), ns)
                finally:
                    ns.pop('__file__', None)
        if mode == 'module':
            _sys.path[0:1] = [_os.getcwd()]
            ns.update(_runpy.run_module(request['module'], run_name='__main__', alter_sys=True))
        elif mode == 'file':
            filename = _os.path.abspath(filename)
            _sys.argv[0] = filename
            _sys.path[0:1] = [_os.path.dirname(filename)]
            ns['__file__'] = filename
            with _polyglot.gil_locked_during_interop(False):
                with open(filename, 'rb') as script:
                    source = script.read()
            exec(compile(source, filename, 'exec'), ns)
        else:
            _sys.path[0:1] = [_os.getcwd()]
            ns.pop('__file__', None)
            source = request.get('code', '')
            if mode == 'repl':
                compiled = _compiler(source, filename, 'single')
                if compiled is None:
                    return _json.dumps({'status': 'incomplete'})
            else:
                compiled = compile(source, filename, 'exec')
            exec(compiled, ns)
        return _json.dumps({'status': 'ok'})
    except KeyboardInterrupt:
        _traceback.print_exc()
        return _json.dumps({'status': 'interrupted'})
    except SystemExit as exc:
        code = exc.code
        if code is not None and not isinstance(code, int):
            print(code, file=_sys.stderr)
            code = 1
        return _json.dumps({'status': 'exit', 'exitCode': code or 0})
    except BaseException:
        _traceback.print_exc()
        return _json.dumps({'status': 'error'})


def _complete(text):
    # Complete names and attribute chains, never evaluate calls or subscripts.
    namespace = vars(_builtins) | _main.__dict__
    class_literal = _re.fullmatch(r"(java\.type\(['\"])([\w.$]*)", text)
    if class_literal:
        opening, prefix = class_literal.groups()
        return _json.dumps([opening + name + opening[-1] + ')'
                            for name in _json.loads(_bridge.completeClass(prefix))])
    if not _re.fullmatch(r'[\w.]*', text):
        return '[]'
    if '.' not in text:
        return _json.dumps(sorted(n for n in namespace.keys() | set(_keyword.kwlist) if n.startswith(text)))
    parent, _, prefix = text.rpartition('.')
    parts = parent.split('.')
    try:
        value = namespace[parts[0]]
        for part in parts[1:]:
            value = getattr(value, part)
        names = [parent + '.' + n for n in dir(value) if n.startswith(prefix)]
        return _json.dumps(sorted(set(names)))
    except (KeyError, AttributeError):
        return _bridge.completeClass(text)
