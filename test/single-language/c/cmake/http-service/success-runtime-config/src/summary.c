#include "../include/summary.h"
#include <ctype.h>
#include <stdio.h>
#include <string.h>

static int hex(char c) {
    if (c >= '0' && c <= '9')
        return c - '0';
    if (c >= 'a' && c <= 'f')
        return c - 'a' + 10;
    if (c >= 'A' && c <= 'F')
        return c - 'A' + 10;
    return -1;
}
int summary_parse(const char *query, Summary *result) {
    *result = (Summary){{1, 2, 3}, 3, 6};
    const char *value = NULL;
    for (const char *p = query; p != NULL && *p;) {
        if (strncmp(p, "values=", 7) == 0) {
            value = p + 7;
            break;
        }
        if (strncmp(p, "values", 6) == 0 && (p[6] == '&' || p[6] == '\0'))
            return 0;
        p = strchr(p, '&');
        if (p != NULL)
            ++p;
    }
    if (value == NULL)
        return 1;
    char decoded[128];
    size_t n = 0;
    for (const char *p = value; *p && *p != '&'; ++p) {
        int c = (unsigned char)*p;
        if (c == '%') {
            if (!p[1] || !p[2] || hex(p[1]) < 0 || hex(p[2]) < 0)
                return 0;
            c = hex(p[1]) * 16 + hex(p[2]);
            p += 2;
        }
        if (n + 1 >= sizeof(decoded) || !(isdigit(c) || c == ','))
            return 0;
        decoded[n++] = (char)c;
    }
    decoded[n] = '\0';
    result->count = 0;
    result->total = 0;
    if (!n)
        return 0;
    const char *p = decoded;
    for (;;) {
        if (!isdigit((unsigned char)*p) || result->count == 20)
            return 0;
        int item = 0;
        while (isdigit((unsigned char)*p)) {
            item = item * 10 + (*p++ - '0');
            if (item > 10000)
                return 0;
        }
        result->items[result->count++] = item;
        result->total += item;
        if (!*p)
            return 1;
        if (*p++ != ',')
            return 0;
    }
}
int summary_json(const Summary *summary, char *buffer, size_t capacity) {
    int used = snprintf(buffer, capacity, "{\"status\":\"ok\",\"items\":[");
    for (size_t i = 0; i < summary->count; ++i) {
        if (used < 0 || (size_t)used >= capacity)
            return 0;
        used += snprintf(buffer + used, capacity - (size_t)used, "%s%d", i ? "," : "", summary->items[i]);
    }
    if (used < 0 || (size_t)used >= capacity)
        return 0;
    used += snprintf(buffer + used, capacity - (size_t)used, "],\"total\":%d}", summary->total);
    return used >= 0 && (size_t)used < capacity;
}
