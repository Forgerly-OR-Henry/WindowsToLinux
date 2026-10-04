package gold.debug.windowstolinux.appapi.ai.voice;

/** Serialized bounded transcript callbacks on the transport thread; return promptly without waiting for session completion. / 在传输线程串行投递的有界转写回调，应及时返回，不得等待会话完成。 */
@FunctionalInterface
public interface VoiceListener {
    /** Observes a delta or confirmed part; cancelled sessions deliver no further normal callbacks. / 观察增量或定稿；取消后不再投递正常回调。
     * @param event ordered observation / 有序观测 */
    void onEvent(VoiceEvent event);
}
