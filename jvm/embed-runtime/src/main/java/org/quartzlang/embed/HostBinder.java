package org.quartzlang.embed;

import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.quartzlang.embed.api.ModuleMetadata;
import org.quartzlang.embed.api.QuartzFunction;
import org.quartzlang.jvm.HostCall;
import org.quartzlang.jvm.Import;
import org.quartzlang.jvm.Sandbox;
import org.quartzlang.jvm.Signature;

/** Binds the {@link QuartzFunction} methods a module imports, converting each argument and result by its type. */
final class HostBinder {
    private HostBinder() {}

    static void bind(Sandbox sandbox, ModuleMetadata.HostBinding binding, ClassLoader loader,
            Map<Class<?>, Object> hosts, Handles handles) {
        Import imported = sandbox.program().findImport(binding.importName()).orElse(null);
        if (imported == null) {
            return;
        }
        Method method = resolve(binding, loader);
        Object receiver = binding.kind() == ModuleMetadata.CallKind.INSTANCE ? receiver(method, hosts) : null;
        Signature signature = imported.signature();
        boolean self = binding.kind() == ModuleMetadata.CallKind.HANDLE;
        java.lang.reflect.Type[] params = method.getGenericParameterTypes();
        int arity = params.length + (self ? 1 : 0);
        if (arity != signature.arity()) {
            throw new EmbedException(binding.importName() + " takes " + signature.arity() + " arguments but "
                    + method + " takes " + arity);
        }
        method.setAccessible(true);
        sandbox.bind(imported.name(), call -> invoke(call, method, receiver, self, params, signature, handles));
    }

    private static Object invoke(HostCall call, Method method, Object receiver, boolean self,
            java.lang.reflect.Type[] params, Signature signature, Handles handles) throws Exception {
        Object target = receiver;
        if (target == null && !self && !Modifier.isStatic(method.getModifiers())) {
            throw new EmbedException(method + " needs an instance of " + method.getDeclaringClass().getName()
                    + ": pass one to QuartzEmbed.Builder.host");
        }
        int first = 0;
        if (self) {
            target = handles.get(call.longArg(0), method.getDeclaringClass());
            first = 1;
        }
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; ++i) {
            org.quartzlang.jvm.Type type = signature.params().get(i + first);
            args[i] = Conversions.hasHandle(type) ? Conversions.toJava(call.arg(i + first), type, params[i], handles)
                    : call.arg(i + first, params[i]);
        }
        Object result;
        try {
            result = method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
        return Conversions.toQuartz(result, signature.result(), handles);
    }

    private static Method resolve(ModuleMetadata.HostBinding binding, ClassLoader loader) {
        Class<?> owner;
        try {
            owner = Class.forName(binding.owner(), false, loader);
        } catch (ClassNotFoundException e) {
            throw new EmbedException("host class " + binding.owner() + " of " + binding.importName()
                    + " is not on the classpath", e);
        }
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getName().equals(binding.method()) && MethodType.methodType(m.getReturnType(), m.getParameterTypes())
                    .toMethodDescriptorString().equals(binding.descriptor())) {
                boolean isStatic = Modifier.isStatic(m.getModifiers());
                if (isStatic != (binding.kind() == ModuleMetadata.CallKind.STATIC)) {
                    break;
                }
                return m;
            }
        }
        throw new EmbedException("host method " + binding.owner() + "." + binding.method() + binding.descriptor()
                + " is gone; rebuild the module against the current classes");
    }

    private static Object receiver(Method method, Map<Class<?>, Object> hosts) {
        Class<?> owner = method.getDeclaringClass();
        for (Map.Entry<Class<?>, Object> e : hosts.entrySet()) {
            if (owner.isAssignableFrom(e.getKey())) {
                return e.getValue();
            }
        }
        try {
            var instance = owner.getDeclaredField("INSTANCE");
            if (Modifier.isStatic(instance.getModifiers())) {
                instance.setAccessible(true);
                return instance.get(null);
            }
        } catch (ReflectiveOperationException ignored) {
            // not a Kotlin object
        }
        return null;
    }
}
