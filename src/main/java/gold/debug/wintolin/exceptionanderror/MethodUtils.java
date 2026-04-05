package gold.debug.wintolin.exceptionanderror;

import java.lang.reflect.Method;

public final class MethodUtils {

    private static final String ERROR_INTERNAL_METHOD = "ERROR_INTERNAL_METHOD";

    private MethodUtils() {
    }

    public static Method getCurrentMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            return clazz.getDeclaredMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            throw new MyException(
                    clazz,
                    null,
                    "Failed to resolve method object: " + methodName,
                    ERROR_INTERNAL_METHOD,
                    e
            );
        }
    }
}
