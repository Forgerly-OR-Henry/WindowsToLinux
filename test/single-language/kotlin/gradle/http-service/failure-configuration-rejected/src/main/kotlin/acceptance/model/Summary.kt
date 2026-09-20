package acceptance.model
import com.google.gson.Gson
data class Summary(val items: List<Int>) {
    val status: String = "ok"
    val total: Int = items.sum()
    fun toJson(): String = Gson().toJson(this)
}
