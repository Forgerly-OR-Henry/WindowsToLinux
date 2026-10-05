package gold.debug.windowstolinux.appapi.file.material;

/** Persisted original material lifecycle; absence grants no custody or access. / 持久原素材生命周期，缺失不授予接管或访问。 */
public enum MaterialState {
    /** No original root exists. / 不存在原根。 */
    ABSENT,
    /** Original custody has no confirmed final manifest. / 原接管尚无已确认最终清单。 */
    CAPTURING,
    /** Original final manifest is durably published. / 原最终清单已持久发布。 */
    PUBLISHED,
    /** Original explicit release has been requested. / 已请求原明确释放。 */
    RELEASE_REQUESTED,
    /** Original physical and durable release are confirmed. / 已确认原物理及持久释放。 */
    RELEASED
}
