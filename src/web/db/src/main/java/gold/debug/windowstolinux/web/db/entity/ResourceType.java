package gold.debug.windowstolinux.web.db.entity;

/**
 * Identifies the workspace resource table selected by the Web repository.
 * <p>标识 Web 仓库选择的工作区资源表。
 */
public enum ResourceType {
/**
 * SERVER classification within resource type.
 * <p>资源类型中的服务器分类。
 */
 SERVER,
/**
 * AI PROFILE classification within resource type.
 * <p>资源类型中的AI配置资料分类。
 */
 AI_PROFILE,
/**
 * SOURCE classification within resource type.
 * <p>资源类型中的源码分类。
 */
 SOURCE,
/**
 * APPLICATION classification within resource type.
 * <p>资源类型中的应用分类。
 */
 APPLICATION,
/**
 * BACKUP classification within resource type.
 * <p>资源类型中的备份分类。
 */
 BACKUP }
