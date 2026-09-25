package acceptance.model
data class Summary(val items: List<Int>) {
    val status: String = "ok"
    val total: Int = items.sum()

    fun toJson(): String =
        "{\"status\":\"ok\",\"items\":[${items.joinToString(",")}],\"total\":$total}"
}
