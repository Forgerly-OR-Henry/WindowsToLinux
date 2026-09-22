#include "../include/router.h"
#include "../include/summary.h"
#include <string.h>
void route_request(const Configuration *configuration, const char *target, Response *response) {
    response->status = configuration->status;
    response->content_type = "text/plain; charset=utf-8";
    response->body = configuration->label;
    if (response->status == 503)
        return;
    const char *query = strchr(target, '?');
    size_t path_length = query ? (size_t)(query - target) : strlen(target);
    int summary_route = path_length == 12 && strncmp(target, "/api/summary", 12) == 0;
    if (summary_route || strcmp(configuration->mode, "json") == 0) {
        Summary summary;
        if (!summary_parse(summary_route && query ? query + 1 : NULL, &summary)) {
            response->status = 400;
            response->body = "invalid-values";
            return;
        }
        if (!summary_json(&summary, response->json, sizeof(response->json))) {
            response->status = 500;
            response->body = "serialization-failed";
            return;
        }
        response->content_type = "application/json; charset=utf-8";
        response->body = response->json;
    }
}
