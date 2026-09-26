package gold.debug.windowstolinux.appapi.db;

/**
 * Author migration from a protected source to an independent target. / 从受保护来源到独立目标的作者迁移逻辑。
 */
public interface SchemaMigration {

    /**
     * Copies the selected read-only source into the independent target. / 将已选只读来源迁移到独立目标。
     * @param source read-only source access retained during migration / 迁移期间保留的只读来源访问
     * @param target owned target of the requested operation / 请求操作的所属目标
     * @throws Exception if validation, execution or actual cleanup fails / 校验、执行或实际清理失败时
     */
    void migrate(SchemaAccess source, SchemaAccess target) throws Exception;
}
