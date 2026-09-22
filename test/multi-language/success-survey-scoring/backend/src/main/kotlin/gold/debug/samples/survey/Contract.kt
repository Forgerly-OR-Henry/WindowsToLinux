package gold.debug.samples.survey

import com.google.gson.*

class BusinessError(val status: Int, message: String) : RuntimeException(message)

fun invalid(message: String): Nothing = throw BusinessError(400, message)

fun string(value: JsonElement?, name: String, max: Int = 200): String {
    if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString)
        invalid("$name 须为文本")
    return value.asString.also { if (it.isBlank() || it.length > max) invalid("$name 为空或过长") }
}

fun integer(
    value: JsonElement?,
    name: String,
    minimum: Int = 0,
    maximum: Int = Int.MAX_VALUE,
): Int {
    if (
        value == null ||
            !value.isJsonPrimitive ||
            !value.asJsonPrimitive.isNumber ||
            !value.toString().matches(Regex("-?\\d+"))
    )
        invalid("$name 须为整数")
    return value.toString().toIntOrNull()?.takeIf { it in minimum..maximum }
        ?: invalid("$name 超出范围")
}

fun obj(value: JsonElement?, name: String): JsonObject =
    if (value?.isJsonObject == true) value.asJsonObject else invalid("$name 须为对象")

fun array(value: JsonElement?, name: String): JsonArray =
    if (value?.isJsonArray == true) value.asJsonArray else invalid("$name 须为数组")

fun boolean(value: JsonElement?, name: String): Boolean {
    if (value?.isJsonPrimitive != true || !value.asJsonPrimitive.isBoolean) invalid("$name 须为布尔值")
    return value.asBoolean
}

object Contract {
    val actors = listOf("林同学", "周同学", "陈老师")

    fun actor(v: JsonElement?): String =
        string(v, "演示身份").also { if (it !in actors) invalid("请选择固定演示身份；不属于身份认证") }

    fun content(raw: JsonElement?): JsonObject {
        val content = obj(raw, "问卷内容")
        string(content["title"], "标题", 120)
        val questions = array(content["questions"], "问题")
        if (questions.size() !in 1..30) invalid("问卷须含 1..30 题")
        val seen = linkedMapOf<String, JsonObject>()
        for (rawQuestion in questions) {
            val q = obj(rawQuestion, "问题")
            val id = string(q["id"], "题号", 40)
            if (!id.matches(Regex("[a-z][a-z0-9_]*")) || seen.containsKey(id))
                invalid("题号须唯一且为小写字母、数字或下划线")
            string(q["label"], "题目", 200)
            boolean(q["required"], "必填")
            val type = string(q["type"], "题型")
            val rule = obj(q["rule"], "评分规则")
            string(rule["dimension"], "维度", 40)
            integer(rule["weight"], "权重", 1, 10)
            boolean(rule["reverse"], "反向计分")
            when (type) {
                "scale" -> {
                    val min = integer(q["min"], "下限", 0, 99)
                    integer(q["max"], "上限", min + 1, 100)
                }
                "single",
                "multi" -> {
                    val options = array(q["options"], "选项")
                    if (options.size() !in 1..10) invalid("每题 1..10 个选项")
                    val values = mutableSetOf<String>()
                    var total = 0
                    for (rawOption in options) {
                        val o = obj(rawOption, "选项")
                        if (!values.add(string(o["value"], "选项值", 40))) invalid("选项值重复")
                        string(o["label"], "选项文本", 100)
                        total += integer(o["score"], "选项分", 0, 100)
                    }
                    if (total == 0) invalid("选项最高分须大于零")
                }
                else -> invalid("题型须为 single、multi 或 scale")
            }
            if (q.has("visibleWhen")) {
                val whenRule = obj(q["visibleWhen"], "显示条件")
                val parent = seen[string(whenRule["question"], "条件题号")] ?: invalid("条件只能引用前面的单选题")
                val value = string(whenRule["equals"], "条件值")
                if (
                    parent["type"].asString != "single" ||
                        parent["options"].asJsonArray.none {
                            it.asJsonObject["value"].asString == value
                        }
                )
                    invalid("显示条件的单选选项无效")
                if (parent.has("visibleWhen")) invalid("显示条件不能引用另一道条件题")
            }
            seen[id] = q
        }
        return content.deepCopy()
    }

    fun answers(content: JsonObject, raw: JsonElement?): JsonObject {
        val input = obj(raw, "答案")
        val result = JsonObject()
        val questions = content["questions"].asJsonArray.map { it.asJsonObject }
        if (input.keySet().any { id -> questions.none { it["id"].asString == id } })
            invalid("答案含未知题号")
        for (q in questions) {
            val id = q["id"].asString
            val condition = q.getAsJsonObject("visibleWhen")
            if (condition != null && input[condition["question"].asString] != condition["equals"])
                continue
            val value = input[id]
            val missing =
                value == null ||
                    value.isJsonNull ||
                    (value.isJsonArray && value.asJsonArray.isEmpty)
            if (missing) {
                if (q["required"].asBoolean) invalid("${q["label"].asString} 为必填题")
                continue
            }
            when (q["type"].asString) {
                "scale" ->
                    result.addProperty(id, integer(value, id, q["min"].asInt, q["max"].asInt))
                "single" -> {
                    val choice = string(value, id)
                    if (
                        q["options"].asJsonArray.none {
                            it.asJsonObject["value"].asString == choice
                        }
                    )
                        invalid("$id 选项无效")
                    result.addProperty(id, choice)
                }
                "multi" -> {
                    val choices = array(value, id).map { string(it, id) }
                    if (
                        choices.toSet().size != choices.size ||
                            choices.any { v ->
                                q["options"].asJsonArray.none {
                                    it.asJsonObject["value"].asString == v
                                }
                            }
                    )
                        invalid("$id 多选答案无效")
                    result.add(id, JsonArray().also { a -> choices.sorted().forEach { a.add(it) } })
                }
            }
        }
        return result
    }
}
