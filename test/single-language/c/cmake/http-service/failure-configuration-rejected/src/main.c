#include "../include/config.h"
#include "../include/http_server.h"
int main(void) {
    Configuration configuration;
    if (!configuration_load(&configuration))
        return 2;
    return serve_http(&configuration);
}
