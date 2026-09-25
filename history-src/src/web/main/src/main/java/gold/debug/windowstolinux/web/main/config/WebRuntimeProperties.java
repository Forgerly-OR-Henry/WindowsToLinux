package gold.debug.windowstolinux.web.main.config;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

import gold.debug.windowstolinux.web.api.config.WebHttpPolicy;
import gold.debug.windowstolinux.web.file.quota.UploadQuota;
import gold.debug.windowstolinux.web.task.model.WebTaskPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validates YAML-supplied runtime, storage, database and secret configuration.
 * <p>校验 YAML 提供的运行、存储、数据库及秘密配置。
 *
 * @param mode selected operating or storage mode / 所选运行或存储模式
 * @param storage storage / 存储
 * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
 * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
 * @param http HTTP protocol / HTTP 协议
 * @param tasks tasks / 任务集合
 * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
 * @param backups backups / 备份集合
 * @param maintenance maintenance / 维护
 */
@ConfigurationProperties(prefix = "w2l", ignoreUnknownFields = false)
public record WebRuntimeProperties(String mode, Storage storage, Secrets secrets, Database database, WebHttpPolicy http,
        WebTaskPolicy tasks, UploadQuota files, UploadQuota backups, Maintenance maintenance) {
    /**
     * Validates and binds the inputs required by web runtime properties.
     * <p>校验并绑定Web运行时属性集合所需输入。
     *
     * @param mode selected operating or storage mode / 所选运行或存储模式
     * @param storage storage / 存储
     * @param secrets credential references or scoped secret-access service / 凭据引用或限定作用域的秘密访问服务
     * @param database reviewed database identity or database operation boundary / 已审阅数据库身份或数据库操作边界
     * @param http HTTP protocol / HTTP 协议
     * @param tasks tasks / 任务集合
     * @param files controlled filesystem access or reviewed file inventory / 受控文件系统访问或已审阅文件清单
     * @param backups backups / 备份集合
     * @param maintenance maintenance / 维护
     * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
     * @throws NullPointerException if a required input is absent / 必需输入缺失时
     */
    public WebRuntimeProperties {
        if (!"internal-test".equals(mode))
            throw new IllegalArgumentException("w2l.mode must be internal-test in Phase 5");
        Objects.requireNonNull(storage);
        Objects.requireNonNull(secrets);
        Objects.requireNonNull(database);
        Objects.requireNonNull(maintenance);
        Objects.requireNonNull(http);
        Objects.requireNonNull(tasks);
        Objects.requireNonNull(files);
        Objects.requireNonNull(backups);
    }
    /**
     * Holds the validated switch controlling Web maintenance access.
     * <p>持有控制 Web 维护访问的已验证开关。
     *
     * @param sourceRetention source retention / 源码保留
     * @param scanValidity validity duration of a discovery snapshot before mandatory reinspection / 发现快照在必须重新检查前的有效时长
     */
    public record Maintenance(Duration sourceRetention, Duration scanValidity) {
        /**
         * Validates and binds the inputs required by maintenance.
         * <p>校验并绑定维护所需输入。
         *
         * @param sourceRetention source retention / 源码保留
         * @param scanValidity validity duration of a discovery snapshot before mandatory reinspection / 发现快照在必须重新检查前的有效时长
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public Maintenance {
            if (sourceRetention == null || sourceRetention.isNegative() || sourceRetention.isZero()
                    || scanValidity == null || scanValidity.isNegative() || scanValidity.isZero())
                throw new IllegalArgumentException("Invalid w2l.maintenance durations");
        }
    }
    /**
     * Holds configured upload, workspace and free-space bounds.
     * <p>持有已配置的上传、工作区及剩余空间边界。
     *
     * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
     * @param minimumFreeBytes minimum free disk space in bytes / 最小磁盘剩余空间，单位为字节
     */
    public record Storage(String root, long minimumFreeBytes) {
        /**
         * Validates and binds the inputs required by storage.
         * <p>校验并绑定存储所需输入。
         *
         * @param root root directory defining the filesystem boundary / 定义文件系统边界的根目录
         * @param minimumFreeBytes minimum free disk space in bytes / 最小磁盘剩余空间，单位为字节
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public Storage {
            if (root == null || root.isBlank() || minimumFreeBytes < 0)
                throw new IllegalArgumentException("Invalid w2l.storage");
        }
    }

    /**
     * Holds the configured location for the Web master-key material.
     * <p>持有 Web 主密钥素材的已配置位置。
     *
     * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
     */
    public record Secrets(Path directory) {
        /**
         * Validates and binds the inputs required by secrets.
         * <p>校验并绑定秘密集合所需输入。
         *
         * @param directory directory within the caller's controlled storage boundary / 调用方受控存储边界内的目录
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public Secrets {
            if (directory == null || !directory.isAbsolute())
                throw new IllegalArgumentException("w2l.secrets.directory must be absolute");
        }
    }

    /**
     * Holds the configured Web database and connection settings.
     * <p>持有已配置的 Web 数据库及连接设置。
     *
     * @param busyTimeout busy timeout / 忙碌超时
     * @param connectionTimeout connection timeout / 连接超时
     */
    public record Database(Duration busyTimeout, Duration connectionTimeout) {
        /**
         * Validates and binds the inputs required by database.
         * <p>校验并绑定数据库所需输入。
         *
         * @param busyTimeout busy timeout / 忙碌超时
         * @param connectionTimeout connection timeout / 连接超时
         * @throws IllegalArgumentException if an input violates the constraints checked by this contract / 输入违反当前契约检查的约束时
         */
        public Database {
            if (busyTimeout == null || busyTimeout.isNegative() || busyTimeout.toMillis() > Integer.MAX_VALUE
                    || connectionTimeout == null || connectionTimeout.toMillis() < 250)
                throw new IllegalArgumentException("Invalid w2l.database timeouts");
        }
    }
}
