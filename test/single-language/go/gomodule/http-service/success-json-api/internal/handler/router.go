package handler
import (
    "encoding/json"
    "net/http"
    "github.com/go-chi/chi/v5"
    "example.com/windowstolinux/fixture/internal/config"
    "example.com/windowstolinux/fixture/internal/service"
)
func New(configuration config.Configuration) http.Handler {
    router := chi.NewRouter()
    serve := func(response http.ResponseWriter, request *http.Request) {
        status, body, contentType := configuration.Status, []byte(configuration.Label), "text/plain; charset=utf-8"
        if status != 503 && (request.URL.Path == "/api/summary" || configuration.Mode == "json") {
            var raw *string
            if request.URL.Path == "/api/summary" {
                if values, found := request.URL.Query()["values"]; found { raw = &values[0] }
            }
            summary, err := service.Summarize(raw)
            if err != nil { status, body = 400, []byte("invalid-values") } else {
                body, err = json.Marshal(summary)
                if err != nil { http.Error(response, "serialization-failed", 500); return }
                contentType = "application/json; charset=utf-8"
            }
        }
        response.Header().Set("Content-Type", contentType)
        response.WriteHeader(status)
        _, _ = response.Write(body)
    }
    router.Get("/api/summary", serve)
    router.Get("/", serve)
    router.Get("/*", serve)
    return router
}
