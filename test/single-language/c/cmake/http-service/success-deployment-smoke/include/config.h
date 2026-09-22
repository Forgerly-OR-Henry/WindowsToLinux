#ifndef FIXTURE_CONFIG_H
#define FIXTURE_CONFIG_H
typedef struct {
    unsigned short port;
    int status;
    const char *mode;
    const char *label;
} Configuration;
int configuration_load(Configuration *configuration);
#endif
