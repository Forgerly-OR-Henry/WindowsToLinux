package acceptance.http
import acceptance.Configuration
import acceptance.service.SummaryService
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
data class Response(val status: Int, val type: String, val body: String)
class Router(private val configuration: Configuration) {
    private val service = SummaryService()
    fun route(target: String): Response {
        if (configuration.status == 503) return Response(503, "text/plain; charset=utf-8", configuration.label)
        val path = target.substringBefore('?')
        if (path == "/api/summary" || configuration.mode == "json") {
            try {
                val query = target.substringAfter('?', "")
                val pair = query.split('&').map { it.split('=', limit = 2) }
                    .firstOrNull { URLDecoder.decode(it[0], StandardCharsets.UTF_8) == "values" }
                val raw = if (path == "/api/summary" && pair != null)
                    URLDecoder.decode(pair.getOrElse(1) { "" }, StandardCharsets.UTF_8) else null
                return Response(200, "application/json; charset=utf-8", service.summarize(raw).toJson())
            } catch (error: IllegalArgumentException) { return Response(400, "text/plain; charset=utf-8", "invalid-values") }
        }
        return Response(200, "text/plain; charset=utf-8", configuration.label)
    }
}
