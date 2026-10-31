"""Keep native Java objects; check host assignments before Java can observe them."""
import ast
import builtins
import codeop
import __future__
import importlib.machinery
import sys
import re
import java

_compile = builtins.compile
_exec = builtins.exec
_setattr = builtins.setattr
_str = str
_print = builtins.print
_float_text = re.compile(r'(?<![\w.])[-+]?(?:\d+\.\d*|\.\d+)(?:[eE][-+]?\d+)?(?![\w.])')
_bridge = None
_future_flags = sum(getattr(__future__, name).compiler_flag for name in __future__.all_feature_names)


def checked_setattr(obj, name, value):
    if java.is_object(obj) or java.is_type(obj):
        error = _bridge.setJavaField(obj, name, value)
        if error is not None:
            raise TypeError(error)
    else:
        _setattr(obj, name, value)


class _WriteTarget:
    def __init__(self, obj):
        object.__setattr__(self, '_obj', obj)

    def __getattr__(self, name):
        return getattr(self._obj, name)

    def __setattr__(self, name, value):
        checked_setattr(self._obj, name, value)


class _Assignments(ast.NodeTransformer):
    def visit_Call(self, node):
        self.generic_visit(node)
        if (isinstance(node.func, ast.Name) and node.func.id == 'str'
                or isinstance(node.func, ast.Attribute) and node.func.attr == 'str'):
            return ast.copy_location(ast.Call(
                func=ast.Name(id='__graalweave_str__', ctx=ast.Load()),
                args=[node.func, *node.args], keywords=node.keywords), node)
        if (isinstance(node.func, ast.Name) and node.func.id == 'print'
                or isinstance(node.func, ast.Attribute) and node.func.attr == 'print'):
            return ast.copy_location(ast.Call(
                func=ast.Name(id='__graalweave_print__', ctx=ast.Load()),
                args=[node.func, *node.args], keywords=node.keywords), node)
        return node

    def visit_Attribute(self, node):
        self.generic_visit(node)
        if isinstance(node.ctx, ast.Store) and not getattr(node, '_graalweave_checked', False):
            node.value = ast.copy_location(ast.Call(
                func=ast.Name(id='__graalweave_write_target__', ctx=ast.Load()),
                args=[node.value], keywords=[]), node.value)
            node._graalweave_checked = True
        return node


def checked_compile(source, filename, mode, flags=0, dont_inherit=False, optimize=-1, *, _feature_version=-1):
    if flags & (ast.PyCF_ONLY_AST | codeop.PyCF_ALLOW_INCOMPLETE_INPUT):
        return _compile(source, filename, mode, flags, dont_inherit, optimize, _feature_version=_feature_version)
    if not dont_inherit:
        flags |= sys._getframe(1).f_code.co_flags & _future_flags
    tree = source if isinstance(source, ast.AST) else _compile(
        source, filename, mode, flags | ast.PyCF_ONLY_AST, True, optimize, _feature_version=_feature_version)
    tree = ast.fix_missing_locations(_Assignments().visit(tree))
    return _compile(tree, filename, mode, flags, True, optimize, _feature_version=_feature_version)


def checked_source_code(loader, fullname):
    # Never load an old unchecked .pyc or write instrumented bytecode for another interpreter.
    filename = loader.get_filename(fullname)
    return checked_compile(loader.get_data(filename), filename, 'exec', dont_inherit=True)


def checked_exec(source, globals=None, locals=None):
    caller = sys._getframe(1)
    if isinstance(source, (str, bytes, ast.AST)):
        source = checked_compile(source, '<string>', 'exec', caller.f_code.co_flags & _future_flags, True)
    if globals is None:
        globals = caller.f_globals
        if locals is None:
            locals = caller.f_locals
    if locals is None:
        locals = globals
    return _exec(source, globals, locals)


def compact_str(converter, *args, **kwargs):
    text = converter(*args, **kwargs)
    if converter is _str:
        return _float_text.sub(lambda match: _str(round(float(match.group()), 2)), text)
    return text


def compact_print(printer, *args, **kwargs):
    if printer is _print:
        args = tuple(compact_str(_str, arg) for arg in args)
    return printer(*args, **kwargs)


def install(bridge):
    global _bridge
    _bridge = bridge
    builtins.__graalweave_write_target__ = _WriteTarget
    builtins.__graalweave_str__ = compact_str
    builtins.__graalweave_print__ = compact_print
    builtins.setattr = checked_setattr
    builtins.compile = checked_compile
    builtins.exec = checked_exec
    importlib.machinery.SourceFileLoader.get_code = checked_source_code
