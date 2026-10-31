package dev.graalweave.core;

import com.google.gson.*;
import java.io.*;
import java.lang.classfile.ClassFile;
import java.lang.reflect.*;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.function.Function;
import java.util.stream.Stream;

/** A shared, lazy bytecode index; reflection is used only for an explicitly requested class. */
public final class ApiExplorer {
    public record Mod(String id, String name, String version, List<Path> roots) {}
    private record Entry(String name, boolean isPublic, boolean exported, Set<String> mods, Set<String> sources) {}
    private final GameAccess game;
    private List<Entry> index;

    public ApiExplorer(GameAccess game) { this.game = game; }

    public JsonArray mods() {
        var result = new JsonArray();
        game.mods().stream().sorted(Comparator.comparing(Mod::id)).forEach(mod -> result.add(Json.object(
                "id", mod.id(), "name", mod.name(), "version", mod.version(),
                "roots", mod.roots().stream().map(p -> p.toUri().toString()).toList())));
        return result;
    }

    public JsonObject classes(String query, String mod, String packageName, boolean publicOnly, int offset, int limit, boolean exact) throws IOException {
        if (mod != null && game.mods().stream().noneMatch(m -> m.id().equals(mod)))
            throw new IllegalArgumentException("Unknown mod: " + mod);
        var matches = entries().stream()
                .filter(e -> matches(e.name(), query, exact))
                .filter(e -> mod == null || e.mods().contains(mod))
                .filter(e -> packageName == null || e.name().startsWith(packageName + "."))
                .filter(e -> !publicOnly || e.isPublic() && e.exported()).toList();
        return page(matches, offset, limit, e -> Json.object("name", e.name(), "public", e.isPublic(), "exported", e.exported(),
                "mods", e.mods(), "sources", e.sources()));
    }

    public List<String> completeClass(String prefix) throws IOException {
        return entries().stream().filter(e -> e.isPublic() && e.exported() && e.name().startsWith(prefix))
                .map(Entry::name).toList();
    }

    public JsonObject inspect(String name, String query, String kind, boolean inherited, int offset, int limit, boolean exact) throws ClassNotFoundException, IOException {
        if (kind != null && !Set.of("constructor", "method", "field", "class").contains(kind))
            throw new IllegalArgumentException("kind must be constructor, method, field or class");
        Class<?> type = Class.forName(name, false, game.classLoader());
        var members = new ArrayList<JsonObject>();
        for (Constructor<?> c : type.getConstructors()) members.add(executable("constructor", c, c.toGenericString()));
        for (Method m : inherited ? type.getMethods() : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers())) continue;
            var item = executable("method", m, m.toGenericString());
            item.addProperty("returns", m.getGenericReturnType().getTypeName());
            item.addProperty("bridge", m.isBridge());
            members.add(item);
        }
        for (Field f : inherited ? type.getFields() : type.getDeclaredFields()) {
            if (!Modifier.isPublic(f.getModifiers())) continue;
            var item = member("field", f, f.toGenericString());
            item.addProperty("type", f.getGenericType().getTypeName());
            members.add(item);
        }
        for (Class<?> nested : inherited ? type.getClasses() : type.getDeclaredClasses()) {
            if (Modifier.isPublic(nested.getModifiers())) members.add(Json.object("kind", "class", "name", nested.getName(),
                    "declaring_class", nested.getDeclaringClass().getName(), "signature", nested.toGenericString(),
                    "modifiers", Modifier.toString(nested.getModifiers())));
        }
        var matches = members.stream().filter(m -> kind == null || m.get("kind").getAsString().equals(kind))
                .filter(m -> matches(m.get("name").getAsString(), query, exact))
                .sorted(Comparator.comparing(m -> m.get("kind").getAsString() + ":" + m.get("signature").getAsString())).toList();
        var result = page(matches, offset, limit, Function.identity());
        result.addProperty("name", type.getName());
        result.addProperty("signature", type.toGenericString());
        result.addProperty("package", type.getPackageName());
        result.addProperty("module", type.getModule().getName());
        result.add("superclass", Json.GSON.toJsonTree(type.getGenericSuperclass() == null ? null : type.getGenericSuperclass().getTypeName()));
        result.add("interfaces", Json.GSON.toJsonTree(Arrays.stream(type.getGenericInterfaces()).map(Type::getTypeName).toList()));
        result.addProperty("enum", type.isEnum());
        result.addProperty("record", type.isRecord());
        var entry = entries().stream().filter(e -> e.name().equals(name)).findFirst();
        result.add("mods", Json.GSON.toJsonTree(entry.map(Entry::mods).orElse(Set.of())));
        result.add("sources", Json.GSON.toJsonTree(entry.map(Entry::sources).orElse(Set.of())));
        var resource = type.getResource("/" + name.replace('.', '/') + ".class");
        result.addProperty("resource", resource == null ? null : resource.toString());
        return result;
    }

    private static boolean matches(String name, String query, boolean exact) {
        return exact ? name.equals(query) || name.substring(name.lastIndexOf('.') + 1).equals(query)
                : name.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    private static JsonObject member(String kind, Member member, String signature) {
        return Json.object("kind", kind, "name", member.getName(), "declaring_class", member.getDeclaringClass().getName(),
                "signature", signature, "modifiers", Modifier.toString(member.getModifiers()), "synthetic", member.isSynthetic());
    }

    private static JsonObject executable(String kind, Executable executable, String signature) {
        var result = member(kind, executable, signature);
        result.add("parameters", Json.GSON.toJsonTree(Arrays.stream(executable.getParameters()).map(p -> Json.object(
                "name", p.isNamePresent() ? p.getName() : null, "type", p.getParameterizedType().getTypeName())).toList()));
        result.add("throws", Json.GSON.toJsonTree(Arrays.stream(executable.getGenericExceptionTypes()).map(Type::getTypeName).toList()));
        result.addProperty("varargs", executable.isVarArgs());
        return result;
    }

    private static <T> JsonObject page(List<T> items, int offset, int limit, Function<T, JsonObject> format) {
        if (offset < 0 || limit < 1) throw new IllegalArgumentException("offset must be nonnegative and limit must be positive");
        int end = (int) Math.min(items.size(), (long) offset + limit);
        return Json.object("total", items.size(), "offset", offset, "limit", limit,
                "next_offset", end < items.size() ? end : null,
                "items", items.subList(Math.min(offset, items.size()), end).stream().map(format).toList());
    }

    private synchronized List<Entry> entries() throws IOException {
        if (index != null) return index;
        var roots = new LinkedHashMap<Path, Set<String>>();
        for (Mod mod : game.mods()) for (Path root : mod.roots()) addRoot(roots, root, mod.id());
        for (String path : System.getProperty("java.class.path").split(File.pathSeparator)) addRoot(roots, Path.of(path), null);
        for (ClassLoader loader = game.classLoader(); loader != null; loader = loader.getParent()) {
            if (loader instanceof URLClassLoader urls) for (var url : urls.getURLs()) {
                if (url.getProtocol().equals("file")) {
                    try { addRoot(roots, Path.of(url.toURI()), null); }
                    catch (java.net.URISyntaxException e) { throw new IOException(e); }
                }
            }
        }
        var found = new TreeMap<String, Entry>();
        for (var root : roots.entrySet()) {
            Path path = root.getKey();
            String source = path.toUri().toString();
            if (Files.isDirectory(path)) {
                try (var files = Files.walk(path)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                        if (path.relativize(file).toString().replace('\\', '/').startsWith("META-INF/")) continue;
                        addClass(found, Files.readAllBytes(file), source, root.getValue(), null);
                    }
                }
            } else if (path.toString().endsWith(".jar")) {
                try (var jar = new JarFile(path.toFile(), true, JarFile.OPEN_READ, Runtime.version())) {
                    for (var entry : jar.versionedStream().filter(e -> e.getName().endsWith(".class") && !e.getName().startsWith("META-INF/")).toList()) {
                        try (var bytes = jar.getInputStream(entry)) { addClass(found, bytes.readAllBytes(), source, root.getValue(), null); }
                    }
                }
            }
        }
        for (var resolved : ModuleLayer.boot().configuration().modules().stream().sorted(Comparator.comparing(m -> m.name())).toList()) {
            Module module = ModuleLayer.boot().findModule(resolved.name()).orElseThrow();
            try (var reader = resolved.reference().open(); Stream<String> names = reader.list()) {
                for (String name : names.filter(n -> n.endsWith(".class")).sorted().toList()) {
                    try (var bytes = reader.open(name).orElseThrow()) { addClass(found, bytes.readAllBytes(), "module:" + module.getName(), Set.of(), module); }
                }
            }
        }
        index = List.copyOf(found.values());
        return index;
    }

    private static void addRoot(Map<Path, Set<String>> roots, Path path, String mod) {
        // Fabric exposes JAR roots as zip file-system paths; merge them with ordinary classpath JARs.
        String uri = path.toUri().toString();
        if (uri.startsWith("jar:") && uri.endsWith("!/")) {
            // Zipfs leaves brackets raw; escape them without decoding existing percent escapes.
            path = Path.of(URI.create(uri.substring(4, uri.length() - 2).replace("[", "%5B").replace("]", "%5D")));
        }
        path = path.toAbsolutePath().normalize();
        if (!Files.exists(path)) return;
        var owners = roots.computeIfAbsent(path, ignored -> new TreeSet<>());
        if (mod != null) owners.add(mod);
    }

    private static void addClass(Map<String, Entry> found, byte[] bytes, String source, Set<String> mods, Module module) {
        var model = ClassFile.of().parse(bytes);
        String name = model.thisClass().asInternalName().replace('/', '.');
        if (name.equals("module-info") || name.endsWith("package-info")) return;
        String packageName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "";
        var entry = found.computeIfAbsent(name, ignored -> new Entry(name, Modifier.isPublic(model.flags().flagsMask()),
                module == null || module.isExported(packageName), new TreeSet<>(), new TreeSet<>()));
        entry.mods().addAll(mods);
        entry.sources().add(source);
    }
}
