package gold.debug.windowstolinux.app.db.bootstrap;

import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Owns protected bootstrap bytes without converting missing or damaged state into a new database. / 持有受保护引导字节，不将缺失或损坏状态转换为新数据库。 */
public final class DatabaseBootstrap implements AutoCloseable {
    /** Database identity protected together with the key. / 与密钥共同保护的数据库身份。 */
    private final UUID generation;

    /** In-memory bootstrap key, wiped on close. / 关闭时清零的内存引导密钥。 */
    private final byte[] key;

    /** Whether the paired files were absent at the locked check. / 持锁检查时配对文件是否均不存在。 */
    private final boolean fresh;

    /** Whether the key was wiped and must never be reused. / 密钥是否已清零且禁止再次使用。 */
    private boolean closed;

    /**
     * Takes ownership of newly decoded key material. / 接管新解码的密钥材料。
     * @param generation bound database identity / 绑定的数据库身份
     * @param key owned key bytes / 接管的密钥字节
     * @param fresh whether initialization is permitted / 是否允许首次初始化
     */
    private DatabaseBootstrap(UUID generation, byte[] key, boolean fresh) {
        this.generation = generation;
        this.key = key;
        this.fresh = fresh;
    }

    /**
     * Loads or creates a matched bootstrap pair while the caller holds the instance lock. / 在调用方持有实例锁时读取或创建匹配的引导材料。
     * @param root validated data root / 已校验的数据根
     * @param protection host-injected current-user protector / 宿主注入的当前用户保护器
     * @return owned bootstrap material / 受管引导材料
     * @throws Exception if pairing, decoding or atomic persistence fails / 配对、解码或原子持久化失败时
     */
    public static DatabaseBootstrap load(Path root, DatabaseProtection protection) throws Exception {
        var database = root.resolve("platform.mv.db");
        var access = root.resolve("db-access.bin");
        boolean hasDatabase = Files.exists(database, LinkOption.NOFOLLOW_LINKS),
                hasAccess = Files.exists(access, LinkOption.NOFOLLOW_LINKS);
        if (hasDatabase != hasAccess)
            throw new java.io.IOException(
                    "Database and protected bootstrap material must be reconciled; neither is recreated");
        for (var path : List.of(database, access))
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)))
                throw new java.io.IOException("Unsafe bootstrap path");
        var json = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        byte[] clear = null;
        if (hasAccess) {
            if (Files.size(access) > 65536)
                throw new java.io.IOException("Bootstrap material exceeds limit");
            try {
                clear = protection.unprotect(Files.readAllBytes(access));
                if (clear == null || clear.length > 65536)
                    throw new java.io.IOException("Invalid protected bootstrap payload");
                var node = json.readTree(clear);
                var fields = new HashSet<String>();
                node.fieldNames().forEachRemaining(fields::add);
                if (!node.isObject() || !fields.equals(Set.of("formatVersion", "databaseGeneration", "key"))
                        || !node.get("formatVersion").isInt() || node.get("formatVersion").intValue() != 1
                        || !node.get("databaseGeneration").isTextual() || !node.get("key").isTextual())
                    throw new java.io.IOException("Invalid bootstrap format");
                var generation = UUID.fromString(node.get("databaseGeneration").textValue());
                var key = Base64.getDecoder().decode(node.get("key").textValue());
                if (key.length != 32) {
                    Arrays.fill(key, (byte) 0);
                    throw new java.io.IOException("Invalid bootstrap key length");
                }
                return new DatabaseBootstrap(generation, key, false);
            } finally {
                if (clear != null)
                    Arrays.fill(clear, (byte) 0);
            }
        }
        try (var candidates = Files.list(root)) {
            if (candidates.anyMatch(path -> path.getFileName().toString().startsWith("platform.")
                    && !path.getFileName().toString().equals("platform.lock")))
                throw new java.io.IOException("Unreconciled database artifacts exist");
        }
        var key = new byte[32];
        new SecureRandom().nextBytes(key);
        var generation = UUID.randomUUID();
        Path temporary = null;
        try {
            var node = json.createObjectNode().put("formatVersion", 1).put("databaseGeneration", generation.toString())
                    .put("key", Base64.getEncoder().encodeToString(key));
            clear = json.writeValueAsBytes(node);
            var encrypted = protection.protect(clear);
            if (encrypted == null || encrypted.length == 0 || encrypted.length > 65536)
                throw new java.io.IOException("Invalid protected bootstrap output");
            temporary = Files.createTempFile(root, "db-access-", ".tmp");
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(encrypted);
                while (buffer.hasRemaining())
                    channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, access, StandardCopyOption.ATOMIC_MOVE);
            temporary = null;
            return new DatabaseBootstrap(generation, key, true);
        } catch (Exception failure) {
            Arrays.fill(key, (byte) 0);
            throw failure;
        } finally {
            if (clear != null)
                Arrays.fill(clear, (byte) 0);
            if (temporary != null)
                Files.deleteIfExists(temporary);
        }
    }

    /** Returns the protected database generation. / 返回受保护的数据库代次。
     * @return protected database generation / 受保护的数据库代次 */
    public UUID generation() {
        return generation;
    }

    /** Reports whether first initialization is permitted. / 报告是否允许首次初始化。
     * @return whether a new database may be created / 是否允许创建新数据库 */
    public boolean fresh() {
        return fresh;
    }

    /**
     * Supplies the private driver password; never expose it to a Mod. / 提供驱动私有口令；不得暴露给插件。
     * @return private driver password / 驱动私有口令
     */
    public synchronized String password() {
        ensureOpen();
        return Base64.getEncoder().encodeToString(key);
    }

    /**
     * Derives an owner password without exposing bootstrap material. / 派生所有者口令，不暴露引导材料。
     * @param purpose fixed application-controlled purpose and identity / 程序控制的固定用途及身份
     * @return private derived driver password / 派生的驱动私有口令
     * @throws java.security.GeneralSecurityException if HMAC is unavailable / HMAC 不可用时
     */
    public synchronized String derive(String purpose) throws java.security.GeneralSecurityException {
        ensureOpen();
        Objects.requireNonNull(purpose);
        var hmac = javax.crypto.Mac.getInstance("HmacSHA256");
        hmac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
        return Base64.getEncoder()
                .encodeToString(hmac.doFinal(purpose.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    /** Rejects use after material has been wiped. / 拒绝在材料清零后继续使用。 */
    private void ensureOpen() {
        if (closed)
            throw new IllegalStateException("Bootstrap material is closed");
    }

    /** Clears the owned bootstrap key. / 清零持有的引导密钥。 */
    @Override
    public synchronized void close() {
        Arrays.fill(key, (byte) 0);
        closed = true;
    }
}
