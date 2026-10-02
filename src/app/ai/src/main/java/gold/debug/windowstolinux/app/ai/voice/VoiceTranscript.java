package gold.debug.windowstolinux.app.ai.voice;

import java.io.IOException;
import java.util.*;

import com.fasterxml.jackson.databind.JsonNode;
import gold.debug.windowstolinux.appapi.ai.*;

/** Commit identities and bounded transcript assembly, guarded by the owning session lock. / 由所属会话锁保护的提交身份及有界转写组装。 */
final class VoiceTranscript {
    /** Maximum complete-session input turns. / 完整会话输入轮次上限。 */
    private static final int MAX_TURNS = 2048;

    /** Maximum outstanding input turns awaiting final text. / 等待定稿文字的未完成输入轮次上限。 */
    private static final int MAX_PENDING = 64;

    /** Original input ordinals awaiting provider item association. / 等待提供方项关联的原输入序号。 */
    private final ArrayDeque<Long> unacknowledged = new ArrayDeque<>();

    /** Provider item identities mapped to frozen local input order. / 映射至冻结本机输入次序的提供方项身份。 */
    private final Map<String, Long> orders = new HashMap<>();

    /** Unconfirmed delta buffers, discarded at termination. / 终止时丢弃的未确认增量缓冲。 */
    private final Map<String, StringBuilder> partials = new HashMap<>();

    /** Confirmed parts sorted independently of provider arrival order. / 按独立于提供方到达顺序排序的已确认片段。 */
    private final TreeMap<Long, VoiceEvent> finals = new TreeMap<>();

    /** Confirmed character count before final separators. / 添加定稿分隔符前的已确认字符数。 */
    private int finalCharacters;

    /** Retained unconfirmed delta character count. / 保留的未确认增量字符数。 */
    private int partialCharacters;

    /** Complete-session accepted input turn count. / 完整会话已接收输入轮次数。 */
    private long committed;

    /** Next monotonically increasing callback delivery ordinal. / 下一个单调递增回调投递序号。 */
    private long sequence;

    /** Last acknowledged item used to verify provider predecessor links. / 用于核对提供方前驱关联的最后已确认项。 */
    private String previousItem;

    /** Checks bounded total and outstanding input turns. / 检查总输入轮次及未定稿轮次上限。
     * @return whether one additional turn fits both limits / 新增一个轮次是否符合两项限制 */
    boolean canCommit() {
        return committed < MAX_TURNS && committed - finals.size() < MAX_PENDING;
    }

    /** Assigns the original local input order before its commit is sent. / 提交发送前分配原本机输入次序。 */
    void commit() {
        unacknowledged.addLast(committed++);
    }

    /** Observes whether any complete input turn exists. / 观察是否有完整输入轮次。
     * @return whether input was committed / 是否已提交输入 */
    boolean hasInput() {
        return committed != 0;
    }

    /** Observes whether all sent commits have acknowledged final parts. / 观察全部已发送提交是否均有已确认定稿片段。
     * @return whether every committed input has a confirmed final / 全部已提交输入是否均有已确认定稿 */
    boolean complete() {
        return unacknowledged.isEmpty() && finals.size() == committed;
    }

    /** Observes whether an uncertain result can preserve confirmed text. / 观察未知结果是否可保留已确认文字。
     * @return whether confirmed parts exist / 是否存在已确认片段 */
    boolean hasConfirmed() {
        return !finals.isEmpty();
    }

    /** Drops unfinished transcript buffers on terminal cleanup. / 终态清理时丢弃未定稿转写缓冲。 */
    void clearPartial() {
        partials.clear();
        partialCharacters = 0;
    }

    /** Associates provider acknowledgement with exactly one original commit. / 将提供方确认关联到唯一原提交。
     * @param event bounded acknowledgement event / 有界确认事件
     * @throws IOException if identity, predecessor or pending input mismatches / 身份、前驱或待确认输入不匹配时 */
    void acknowledge(JsonNode event) throws IOException {
        String id = text(event, "item_id");
        if (orders.containsKey(id) || unacknowledged.isEmpty())
            throw new IOException("Unexpected commit acknowledgement");
        var previous = event.get("previous_item_id");
        if (previous != null && !previous.isNull()
            && (!previous.isTextual() || !Objects.equals(previousItem, previous.textValue())))
            throw new IOException("Commit predecessor mismatched");
        orders.put(id, unacknowledged.removeFirst());
        previousItem = id;
    }

    /** Accumulates deltas and replaces them with finals; an identical duplicate produces no callback. / 累积增量并由定稿替换，相同重复不产生回调。
     * @param event bounded provider transcript event / 有界提供方转写事件
     * @param last whether the event confirms this part / 事件是否定稿本片段
     * @return ordered observation, or null for an identical final duplicate / 有序观测，相同重复定稿时为 null
     * @throws IOException if association, content or bounds violate the protocol / 关联、内容或限制违反协议时 */
    VoiceEvent accept(JsonNode event, boolean last) throws IOException {
        var index = event.path("content_index");
        if (!index.isIntegralNumber() || !index.canConvertToInt() || index.intValue() != 0)
            throw new IOException("Unsupported transcript part");
        String id = text(event, "item_id");
        Long order = orders.get(id);
        if (order == null) throw new IOException("Transcript has no committed input");
        String value = text(event, last ? "transcript" : "delta");
        var prior = finals.get(order);
        if (prior != null) {
            if (last && prior.text().equals(value)) return null;
            throw new IOException("Finalized part changed");
        }
        if (value.length() > 262_144) throw new IOException("Transcript part exceeded bound");
        if (last) {
            if (value.length() > VoiceProtocol.MAX_RESPONSE_BYTES - finalCharacters - finals.size())
                throw new IOException("Final transcript exceeded bound including separators");
            finalCharacters += value.length();
            var partial = partials.remove(id);
            if (partial != null) partialCharacters -= partial.length();
        } else {
            var partial = partials.computeIfAbsent(id, ignored -> new StringBuilder());
            if (value.length() > 262_144 - partial.length()
                || value.length() > VoiceProtocol.MAX_RESPONSE_BYTES - partialCharacters)
                throw new IOException("Partial transcript exceeded bound");
            partial.append(value);
            partialCharacters += value.length();
        }
        var observation = new VoiceEvent(id, 0, sequence++, order, last, value);
        if (last) finals.put(order, observation);
        return observation;
    }

    /** Assembles confirmed parts in original input order regardless of arrival order. / 不论到达顺序，按原输入顺序组装已确认片段。
     * @param modelId frozen platform selection / 冻结的平台所选身份
     * @return confirmed text with ordered final parts / 带有序定稿片段的已确认文字 */
    VoiceResponse confirmed(String modelId) {
        var parts = List.copyOf(finals.values());
        return new VoiceResponse(String.join("\n", parts.stream().map(VoiceEvent::text).toList()), modelId, parts);
    }

    /** Requires a textual protocol field and bounded item identity. / 要求文本协议字段及有界项身份。
     * @param node provider event object / 提供方事件对象
     * @param field required field name / 必需字段名称
     * @return actual textual field / 实际文本字段
     * @throws IOException if the field or item identity is invalid / 字段或项身份无效时 */
    private static String text(JsonNode node, String field) throws IOException {
        var value = node.get(field);
        if (value == null || !value.isTextual()) throw new IOException("Missing event field");
        String text = value.textValue();
        if (field.equals("item_id") && (text.isBlank() || text.length() > 256))
            throw new IOException("Invalid item identity");
        return text;
    }
}
