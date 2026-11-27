package com.custommenu.core;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Küçük yansıma (reflection) yardımcısı: kütüphane sürümü farkları derlemeyi bozmasın diye. */
public final class Refl {
    private Refl() { }

    public static Object call(Object o, String name, Object... args) throws Exception {
        return invoke(o.getClass(), o, name, args);
    }
    public static Object callStatic(String cls, String name, Object... args) throws Exception {
        return invoke(Class.forName(cls), null, name, args);
    }
    public static Object newInstance(String cls, Object... args) throws Exception {
        for (java.lang.reflect.Constructor<?> c : Class.forName(cls).getConstructors()) {
            if (c.getParameterCount() != args.length) continue;
            try { return c.newInstance(args); }
            catch (IllegalArgumentException e) { /* sıradaki */ }
            catch (InvocationTargetException e) { throw unwrap(e); }
        }
        throw new NoSuchMethodException(cls + " yapıcısı");
    }
    private static Object invoke(Class<?> c, Object o, String name, Object[] args) throws Exception {
        Exception last = null;
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try { try { m.setAccessible(true); } catch (Throwable ignored) { } return m.invoke(o, args); }
            catch (IllegalArgumentException e) { last = e; }
            catch (InvocationTargetException e) { throw unwrap(e); }
        }
        throw last != null ? last : new NoSuchMethodException(c.getName() + "." + name);
    }
    private static Exception unwrap(InvocationTargetException e) {
        Throwable t = e.getCause();
        return t instanceof Exception ? (Exception) t : new RuntimeException(t);
    }
}
