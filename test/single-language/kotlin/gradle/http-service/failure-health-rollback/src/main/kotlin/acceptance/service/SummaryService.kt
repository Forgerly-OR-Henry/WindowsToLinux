package acceptance.service
import acceptance.model.Summary
class SummaryService {
    fun summarize(raw: String?): Summary {
        val tokens = (raw ?: "1,2,3").split(',')
        require(tokens.size in 1..20) { "invalid-values" }
        val items = tokens.map { token ->
            require(token.matches(Regex("[0-9]{1,10}"))) { "invalid-values" }
            val value = token.toLong()
            require(value in 0..10000) { "invalid-values" }
            value.toInt()
        }
        return Summary(items)
    }
}
