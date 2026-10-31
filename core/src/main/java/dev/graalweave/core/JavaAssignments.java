package dev.graalweave.core;

import org.graalvm.polyglot.*;
import java.lang.reflect.*;
import java.util.*;

/** Validate before touching a host field, including fields erased to Object by Java generics. */
public final class JavaAssignments {
    private JavaAssignments() {}

    public static final HostAccess HOST_ACCESS = HostAccess.newBuilder(HostAccess.ALL)
            .targetTypeMapping(Double.class, Float.class,
                    n -> !Double.isFinite(n) || Math.abs(n) <= Float.MAX_VALUE,
                    Double::floatValue, HostAccess.TargetMappingPrecedence.LOW).build();

    public static void set(Value receiver, String name, Value value) throws ReflectiveOperationException {
        Object owner = receiver.asHostObject();
        Class<?> type = owner instanceof Class<?> c ? c : owner.getClass();
        Field field = type.getField(name);
        Object instance = Modifier.isStatic(field.getModifiers()) ? null : owner;
        if (Modifier.isFinal(field.getModifiers())) throw new IllegalArgumentException("Cannot assign final field " + field);
        Type target = resolve(field.getGenericType(), type, field.getDeclaringClass());
        if (target instanceof TypeVariable<?>) {
            // Reified holders such as Baritone Setting<T> publish their value class.
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Class";
            try {
                Method method = type.getMethod(getter);
                if (method.getReturnType() == Class.class) target = (Class<?>) method.invoke(instance);
            } catch (NoSuchMethodException ignored) { /* No reified type: reject rather than guess. */ }
        }
        Object converted;
        try { converted = convert(value, target); }
        catch (IllegalArgumentException | ClassCastException e) {
            throw new IllegalArgumentException(type.getTypeName() + "." + name + " expects "
                    + target.getTypeName() + ": " + e.getMessage(), e);
        }
        field.set(instance, converted);
    }

    private static Type resolve(Type target, Class<?> owner, Class<?> declaring) {
        Map<TypeVariable<?>, Type> bindings = new HashMap<>();
        for (Class<?> current = owner; current != null && current != declaring; current = current.getSuperclass()) {
            if (current.getGenericSuperclass() instanceof ParameterizedType parent) {
                TypeVariable<?>[] variables = ((Class<?>) parent.getRawType()).getTypeParameters();
                Type[] arguments = parent.getActualTypeArguments();
                for (int i = 0; i < variables.length; i++)
                    bindings.put(variables[i], substitute(arguments[i], bindings));
            }
        }
        return substitute(target, bindings);
    }

    private static Type substitute(Type target, Map<TypeVariable<?>, Type> bindings) {
        while (target instanceof TypeVariable<?> variable && bindings.containsKey(variable)) target = bindings.get(variable);
        if (target instanceof ParameterizedType p) {
            Type[] args = Arrays.stream(p.getActualTypeArguments()).map(t -> substitute(t, bindings)).toArray(Type[]::new);
            return new ParameterizedType() {
                public Type getRawType() { return p.getRawType(); }
                public Type getOwnerType() { return p.getOwnerType(); }
                public Type[] getActualTypeArguments() { return args.clone(); }
                public String getTypeName() { return p.getRawType().getTypeName() + Arrays.toString(args); }
            };
        }
        return target;
    }

    private static Object convert(Value value, Type target) {
        if (target instanceof TypeVariable<?> || target instanceof WildcardType)
            throw new IllegalArgumentException("generic type is not available at runtime; use a typed Java setter");
        if (target instanceof ParameterizedType p) {
            Class<?> raw = (Class<?>) p.getRawType();
            if (value.isNull()) return null;
            if (List.class.isAssignableFrom(raw) && value.hasArrayElements() && raw.isAssignableFrom(ArrayList.class)) {
                var list = new ArrayList<>();
                for (long i = 0; i < value.getArraySize(); i++) list.add(convert(value.getArrayElement(i), p.getActualTypeArguments()[0]));
                return list;
            }
            throw new IllegalArgumentException("cannot validate parameterized value " + p.getTypeName());
        }
        if (!(target instanceof Class<?> type)) throw new IllegalArgumentException("unsupported type " + target);
        if (value.isNull()) {
            if (type.isPrimitive()) throw new IllegalArgumentException("null cannot be assigned to a primitive");
            return null;
        }
        if (type == float.class || type == Float.class) {
            if (!value.isNumber() || !value.fitsInDouble()) throw new IllegalArgumentException("expected a number");
            double n = value.asDouble();
            if (Double.isFinite(n) && Math.abs(n) > Float.MAX_VALUE) throw new IllegalArgumentException("number is outside float range");
            return (float) n;
        }
        if (type.isArray() && value.hasArrayElements()) {
            int length = Math.toIntExact(value.getArraySize());
            Object array = Array.newInstance(type.componentType(), length);
            for (int i = 0; i < length; i++) Array.set(array, i, convert(value.getArrayElement(i), type.componentType()));
            return array;
        }
        try { return value.as(type); }
        catch (RuntimeException e) { throw new IllegalArgumentException("cannot convert " + value + " to " + type.getTypeName(), e); }
    }
}
