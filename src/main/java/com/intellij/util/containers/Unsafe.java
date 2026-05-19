package com.intellij.util.containers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Drop-in replacement for the bundled IntelliJ Platform {@code Unsafe} wrapper.
 * The original delegates to {@code sun.misc.Unsafe}, which is terminally deprecated
 * in Java 23+. This version delegates to {@code jdk.internal.misc.Unsafe} via
 * reflection (requires {@code --add-opens java.base/jdk.internal.misc=ALL-UNNAMED}).
 */
public final class Unsafe {

    private static final Object THE_UNSAFE;
    private static final Method OBJECT_FIELD_OFFSET;
    private static final Method COMPARE_AND_SET_INT;
    private static final Method COMPARE_AND_SET_LONG;
    private static final Method COMPARE_AND_SET_REFERENCE;
    private static final Method GET_AND_ADD_INT;
    private static final Method GET_REFERENCE_VOLATILE;
    private static final Method PUT_REFERENCE_VOLATILE;
    private static final Method ARRAY_INDEX_SCALE;
    private static final Method ARRAY_BASE_OFFSET;
    private static final Method COPY_MEMORY;

    static {
        try {
            Class<?> cls = Class.forName("jdk.internal.misc.Unsafe");
            Field f = cls.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            THE_UNSAFE = f.get(null);

            OBJECT_FIELD_OFFSET     = m(cls, "objectFieldOffset", Field.class);
            COMPARE_AND_SET_INT     = m(cls, "compareAndSetInt", Object.class, long.class, int.class, int.class);
            COMPARE_AND_SET_LONG    = m(cls, "compareAndSetLong", Object.class, long.class, long.class, long.class);
            COMPARE_AND_SET_REFERENCE = m(cls, "compareAndSetReference", Object.class, long.class, Object.class, Object.class);
            GET_AND_ADD_INT         = m(cls, "getAndAddInt", Object.class, long.class, int.class);
            GET_REFERENCE_VOLATILE  = m(cls, "getReferenceVolatile", Object.class, long.class);
            PUT_REFERENCE_VOLATILE  = m(cls, "putReferenceVolatile", Object.class, long.class, Object.class);
            ARRAY_INDEX_SCALE       = m(cls, "arrayIndexScale", Class.class);
            ARRAY_BASE_OFFSET       = m(cls, "arrayBaseOffset", Class.class);
            COPY_MEMORY             = m(cls, "copyMemory", Object.class, long.class, Object.class, long.class, long.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Method m(Class<?> cls, String name, Class<?>... params) throws Exception {
        Method method = cls.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }

    private Unsafe() {}

    public static long objectFieldOffset(Field field) {
        try { return (long) OBJECT_FIELD_OFFSET.invoke(THE_UNSAFE, field); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static boolean compareAndSwapInt(Object o, long offset, int expected, int update) {
        try { return (boolean) COMPARE_AND_SET_INT.invoke(THE_UNSAFE, o, offset, expected, update); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static boolean compareAndSwapLong(Object o, long offset, long expected, long update) {
        try { return (boolean) COMPARE_AND_SET_LONG.invoke(THE_UNSAFE, o, offset, expected, update); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static boolean compareAndSwapObject(Object o, long offset, Object expected, Object update) {
        try { return (boolean) COMPARE_AND_SET_REFERENCE.invoke(THE_UNSAFE, o, offset, expected, update); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static int getAndAddInt(Object o, long offset, int delta) {
        try { return (int) GET_AND_ADD_INT.invoke(THE_UNSAFE, o, offset, delta); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static Object getObjectVolatile(Object o, long offset) {
        try { return GET_REFERENCE_VOLATILE.invoke(THE_UNSAFE, o, offset); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static void putObjectVolatile(Object o, long offset, Object value) {
        try { PUT_REFERENCE_VOLATILE.invoke(THE_UNSAFE, o, offset, value); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static int arrayIndexScale(Class<?> cls) {
        try { return (int) ARRAY_INDEX_SCALE.invoke(THE_UNSAFE, cls); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static int arrayBaseOffset(Class<?> cls) {
        try { return (int) (long) ARRAY_BASE_OFFSET.invoke(THE_UNSAFE, cls); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    public static void copyMemory(Object src, long srcOffset, Object dst, long dstOffset, long bytes) {
        try { COPY_MEMORY.invoke(THE_UNSAFE, src, srcOffset, dst, dstOffset, bytes); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
}
