#include "../include/config.h"
#include <stdlib.h>
#include <string.h>
int configuration_load(Configuration *configuration) {
    const char *port = getenv("PORT");
    if (!port || !*port || strspn(port, "0123456789") != strlen(port))
        return 0;
    char *end;
    long parsed = strtol(port, &end, 10);
    if (*end || parsed < 1 || parsed > 65535)
        return 0;
    configuration->port = (unsigned short)parsed;
    configuration->status = 200;
    configuration->mode = "smoke";
    const char *label = getenv("FIXTURE_LABEL");
    configuration->label =
        strcmp(configuration->mode, "config") == 0 ? (label ? label : "runtime-config-default") : "deployment-smoke-ok";
    return 1;
}
