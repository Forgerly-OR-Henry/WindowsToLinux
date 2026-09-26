package gold.debug.windowstolinux.app.mod.service;

import java.lang.reflect.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;

/** Copies bounded service values without allowing live provider resources to cross the call. / 复制有界服务值，不让活动提供方资源跨越调用。 */
public final class ServiceSnapshot {
    /** Exact trusted immutable JDK scalar classes. / 精确可信不可变 JDK 标量类。 */
    private static final Set<Class<?>> SCALARS = Set.of(String.class, Boolean.class, Byte.class, Short.class,
            Integer.class, Long.class, Float.class, Double.class, Character.class, BigInteger.class, BigDecimal.class,
            UUID.class, Instant.class, Duration.class, LocalDate.class, LocalTime.class, LocalDateTime.class,
            OffsetTime.class, OffsetDateTime.class, ZonedDateTime.class, Year.class, YearMonth.class, MonthDay.class);
    /** Prevents utility construction. / 禁止工具构造。 */
    private ServiceSnapshot() {
    }

    /** Produces a detached immutable value graph before resource claims are released. / 资源申请释放前生成分离的不可变值图。
     * @param value supplied boundary value / 提供的边界值
     * @param publicType policy for public record and enum identity / 公开记录及枚举身份策略
     * @return copied immutable value / 复制的不可变值
     * @throws ReflectiveOperationException if a record cannot be safely reconstructed / 无法安全重建记录时 */
    public static Object copy(Object value, Predicate<Class<?>> publicType) throws ReflectiveOperationException {
        return copy(value, publicType, new IdentityHashMap<>(), new int[]{4096, 1024 * 1024}, 0);
    }

    /** Copies only bounded acyclic values, never streams, callbacks, JDBC or futures. / 仅复制有界无环值，不复制流、回调、JDBC 或 Future。
     * @param value current value / 当前值
     * @param publicType exact public type policy / 精确公开类型策略
     * @param visiting current identity path / 当前身份路径
     * @param budget remaining nodes and text bytes / 剩余节点及文字字节
     * @param depth current nesting / 当前嵌套
     * @return detached value / 分离值
     * @throws ReflectiveOperationException if reconstruction fails / 重建失败时 */
    private static Object copy(Object value, Predicate<Class<?>> publicType, IdentityHashMap<Object, Boolean> visiting,
            int[] budget, int depth) throws ReflectiveOperationException {
        if (--budget[0] < 0 || depth > 64)
            throw new IllegalArgumentException("Service value exceeds graph limits");
        if (value == null)
            return null;
        var type = value.getClass();
        if (SCALARS.contains(type) || value instanceof Enum<?> item && publicType.test(item.getDeclaringClass())
                || value instanceof Path path && path.getFileSystem() == FileSystems.getDefault()
                        && type.getClassLoader() == null) {
            budget[1] -= value.toString().getBytes(StandardCharsets.UTF_8).length;
            if (budget[1] < 0)
                throw new IllegalArgumentException("Service value exceeds byte budget");
            return value;
        }
        if (visiting.put(value, true) != null)
            throw new IllegalArgumentException("Cyclic service values are not permitted");
        try {
            if (value instanceof Optional<?> optional)
                return optional.isEmpty()
                        ? Optional.empty()
                        : Optional.ofNullable(copy(optional.get(), publicType, visiting, budget, depth + 1));
            if (value instanceof List<?> list) {
                if (list.size() > budget[0])
                    throw new IllegalArgumentException("Service list exceeds its budget");
                var result = new ArrayList<>();
                for (var item : list)
                    result.add(copy(item, publicType, visiting, budget, depth + 1));
                return List.copyOf(result);
            }
            if (value instanceof Set<?> set) {
                if (set.size() > budget[0])
                    throw new IllegalArgumentException("Service set exceeds its budget");
                var result = new HashSet<>();
                for (var item : set)
                    if (!result.add(copy(item, publicType, visiting, budget, depth + 1)))
                        throw new IllegalArgumentException("Service set copy collides");
                return Set.copyOf(result);
            }
            if (value instanceof Map<?, ?> map) {
                if (map.size() > budget[0] / 2)
                    throw new IllegalArgumentException("Service map exceeds its budget");
                var result = new HashMap<>();
                for (var entry : map.entrySet()) {
                    var key = copy(entry.getKey(), publicType, visiting, budget, depth + 1);
                    if (result.put(key, copy(entry.getValue(), publicType, visiting, budget, depth + 1)) != null)
                        throw new IllegalArgumentException("Service map copy collides");
                }
                return Map.copyOf(result);
            }
            if (!type.isRecord() || !Modifier.isPublic(type.getModifiers()) || !publicType.test(type))
                throw new IllegalArgumentException("Only public immutable service values may cross calls");
            var components = type.getRecordComponents();
            var arguments = new Object[components.length];
            var types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                arguments[i] = copy(components[i].getAccessor().invoke(value), publicType, visiting, budget, depth + 1);
            }
            return type.getConstructor(types).newInstance(arguments);
        } finally {
            visiting.remove(value);
        }
    }
}
