#ifndef FIXTURE_ROUTER_H
#define FIXTURE_ROUTER_H
#include "config.h"
typedef struct { int status; const char *content_type; const char *body; char json[512]; } Response;
void route_request(const Configuration *configuration, const char *target, Response *response);
#endif
