package acceptance
data class Configuration(val port: Int, val mode: String, val status: Int, val label: String) {
    companion object {
        fun load(): Configuration {
            val raw = System.getenv("PORT") ?: ""
            val port = raw.toIntOrNull()
            require(raw.matches(Regex("[0-9]{1,5}")) && port != null && port in 1..65535) {
                "Invalid PORT"
            }
            val mode = "json"
            val label =
                if (mode == "config") System.getenv("FIXTURE_LABEL") ?: "runtime-config-default"
                else "deployment-smoke-ok"
            return Configuration(port, mode, 200, label)
        }
    }
}
