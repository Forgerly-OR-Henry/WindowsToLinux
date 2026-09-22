#ifndef FIXTURE_SUMMARY_H
#define FIXTURE_SUMMARY_H
#include <stddef.h>
typedef struct {
    int items[20];
    size_t count;
    int total;
} Summary;
int summary_parse(const char *query, Summary *result);
int summary_json(const Summary *summary, char *buffer, size_t capacity);
#endif
