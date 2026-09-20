#include "worker.h"
#include "format.h"
#include <errno.h>
#include <inttypes.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    const char *input, *type;
    uint64_t max_bytes;
    int64_t min, max;
} Options;
typedef struct {
    uint64_t measurements, events, selected, selected_measurements, selected_events, event_bytes;
    int64_t sum, min, max;
    unsigned flags;
    unsigned sample_count;
    uint32_t sample_ids[5];
    size_t sample_sizes[5];
    unsigned char samples[5][4096];
} Summary;
static int parse_options(int argc, char **argv, Options *o) {
    *o = (Options){NULL, "all", 268435456, INT64_MIN, INT64_MAX};
    for (int i = 1; i < argc; i += 2) {
        if (i + 1 >= argc)
            return 0;
        char *end = NULL;
        errno = 0;
        if (!strcmp(argv[i], "--input"))
            o->input = argv[i + 1];
        else if (!strcmp(argv[i], "--type"))
            o->type = argv[i + 1];
        else if (!strcmp(argv[i], "--min")) {
            o->min = strtoll(argv[i + 1], &end, 10);
            if (errno || !*argv[i + 1] || *end)
                return 0;
        } else if (!strcmp(argv[i], "--max")) {
            o->max = strtoll(argv[i + 1], &end, 10);
            if (errno || !*argv[i + 1] || *end)
                return 0;
        } else if (!strcmp(argv[i], "--max-bytes")) {
            o->max_bytes = strtoull(argv[i + 1], &end, 10);
            if (errno || !*argv[i + 1] || *end || !o->max_bytes || o->max_bytes > 1073741824)
                return 0;
        } else
            return 0;
    }
    return o->input && o->min <= o->max &&
           (!strcmp(o->type, "all") || !strcmp(o->type, "measurement") || !strcmp(o->type, "event"));
}
static int inspect(Reader *r, const Options *o, Summary *s, uint32_t *blocks) {
    unsigned char header[24], data[4100];
    if (!read_bytes(r, header, 24, 1, 0))
        return 0;
    if (memcmp(header, "WTL2", 4))
        return fail(r, "invalid file magic", 0, 2);
    if (little16(header + 4) != 2)
        return fail(r, "unsupported version or byte order", 4, 2);
    if (little16(header + 6) != 24)
        return fail(r, "invalid header length", 6, 2);
    *blocks = little32(header + 8);
    uint64_t records = little64(header + 12);
    if (*blocks > 65536 || records > 1000000 || little32(header + 20) != 0)
        return fail(r, "invalid block/record count or reserved field", 8, 2);
    for (uint32_t b = 0; b < *blocks; b++) {
        r->block = b;
        uint64_t block_offset = r->offset;
        unsigned char block[16];
        if (!read_bytes(r, block, 16, 1, 0))
            return 0;
        if (memcmp(block, "BLK2", 4))
            return fail(r, "invalid block magic", block_offset, 2);
        uint32_t bytes = little32(block + 4), count = little32(block + 8),
                 expected_crc = little32(block + 12);
        if (!bytes || bytes > 1048576 || !count || count > bytes / 8 || count > 1000000 - r->records)
            return fail(r, "invalid block length or record count", block_offset + 4, 2);
        uint64_t end = r->offset + bytes;
        r->block_crc = 0xffffffffu;
        for (uint32_t n = 0; n < count; n++) {
            uint64_t record_offset = r->offset;
            unsigned char record[4];
            if (end - r->offset < 4)
                return fail(r, "record header crosses block boundary", record_offset, 2);
            if (!read_bytes(r, record, 4, 1, 1))
                return 0;
            unsigned kind = record[0], flags = record[1], length = little16(record + 2);
            if (kind != 1 && kind != 2)
                return fail(r, "unknown record type", record_offset, 2);
            if (flags > 3)
                return fail(r, "unsupported record flags", record_offset + 1, 2);
            if ((kind == 1 && length != 12) || (kind == 2 && (length < 4 || length > 4100)) ||
                length > end - r->offset)
                return fail(r, "invalid record length", record_offset + 2, 2);
            if (!read_bytes(r, data, length, 1, 1))
                return 0;
            r->records++;
            if (kind == 1) {
                s->measurements++;
                uint64_t raw = little64(data + 4);
                int64_t value = raw <= INT64_MAX ? (int64_t)raw : -(int64_t)(UINT64_MAX - raw) - 1;
                if (strcmp(o->type, "event") && value >= o->min && value <= o->max) {
                    if ((value > 0 && s->sum > INT64_MAX - value) ||
                        (value < 0 && s->sum < INT64_MIN - value))
                        return fail(r, "selected measurement sum overflows int64", record_offset, 2);
                    if (!s->selected_measurements || value < s->min)
                        s->min = value;
                    if (!s->selected_measurements || value > s->max)
                        s->max = value;
                    s->sum += value;
                    s->selected_measurements++;
                    s->selected++;
                    s->flags |= flags;
                }
            } else {
                s->events++;
                if (!valid_utf8(data + 4, length - 4))
                    return fail(r, "invalid event UTF-8", record_offset + 8, 2);
                if (strcmp(o->type, "measurement")) {
                    s->selected_events++;
                    s->selected++;
                    s->event_bytes += length - 4;
                    s->flags |= flags;
                    if (s->sample_count < 5) {
                        unsigned i = s->sample_count++;
                        s->sample_ids[i] = little32(data);
                        s->sample_sizes[i] = length - 4;
                        memcpy(s->samples[i], data + 4, length - 4);
                    }
                }
            }
        }
        if (r->offset != end)
            return fail(r, "block payload length or count mismatch", r->offset, 2);
        if ((~r->block_crc) != expected_crc)
            return fail(r, "block CRC mismatch", block_offset + 12, 2);
    }
    r->block = UINT32_MAX;
    if (r->records != records)
        return fail(r, "file record count mismatch", 12, 2);
    uint64_t checksum_offset = r->offset;
    if (!read_bytes(r, data, 4, 0, 0))
        return 0;
    if (little32(data) != (~r->file_crc))
        return fail(r, "file CRC mismatch", checksum_offset, 2);
    if (fgetc(r->file) != EOF)
        return fail(r, "trailing bytes after file checksum", r->offset, 2);
    if (ferror(r->file))
        return fail(r, "file read error", r->offset, 3);
    return 1;
}
int run(int argc, char **argv) {
    Options options;
    if (!parse_options(argc, argv, &options)) {
        fprintf(
            stderr,
            "invalid options: --input FILE [--type all|measurement|event --min N --max N --max-bytes N]\n");
        return 2;
    }
    Reader reader = {
        open_utf8(options.input), 0, options.max_bytes, 0, 0xffffffffu, 0xffffffffu, UINT32_MAX, NULL, 0, 0};
    Summary summary = {0};
    uint32_t blocks = 0;
    puts("{\"protocolVersion\":2,\"component\":\"c-binary\",\"type\":\"start\",\"sequence\":0}");
    int ok = reader.file ? inspect(&reader, &options, &summary, &blocks)
                         : fail(&reader, "cannot open input", 0, 3);
    if (reader.file)
        fclose(reader.file);
    if (!ok) {
        printf("{\"protocolVersion\":2,\"component\":\"c-binary\",\"type\":\"error\",\"sequence\":1,"
               "\"exitCode\":%d,\"error\":{\"offset\":%" PRIu64 ",\"block\":",
               reader.error_code, reader.error_offset);
        if (reader.block == UINT32_MAX)
            printf("null");
        else
            printf("%" PRIu32, reader.block);
        printf(",\"message\":");
        json_string((const unsigned char *)reader.error, strlen(reader.error));
        puts("}}");
        fprintf(stderr, "block=%" PRIu32 " offset=%" PRIu64 ": %s\n", reader.block, reader.error_offset,
                reader.error);
    } else {
        printf("{\"protocolVersion\":2,\"component\":\"c-binary\",\"type\":\"summary\",\"sequence\":1,"
               "\"blocks\":%" PRIu32 ",\"records\":%" PRIu64 ",\"bytes\":%" PRIu64
               ",\"measurements\":%" PRIu64 ",\"events\":%" PRIu64 ",\"selected\":%" PRIu64
               ",\"selectedMeasurements\":%" PRIu64 ",\"selectedEvents\":%" PRIu64 ",\"eventBytes\":%" PRIu64
               ",\"sum\":%" PRId64 ",\"flagsOr\":%u,\"checksum\":\"%08" PRIx32 "\",\"min\":",
               blocks, reader.records, reader.offset, summary.measurements, summary.events, summary.selected,
               summary.selected_measurements, summary.selected_events, summary.event_bytes, summary.sum,
               summary.flags, ~reader.file_crc);
        if (summary.selected_measurements)
            printf("%" PRId64 ",\"max\":%" PRId64, summary.min, summary.max);
        else
            printf("null,\"max\":null");
        printf(",\"eventSamples\":[");
        for (unsigned i = 0; i < summary.sample_count; i++) {
            if (i)
                putchar(',');
            printf("{\"id\":%" PRIu32 ",\"text\":", summary.sample_ids[i]);
            json_string(summary.samples[i], summary.sample_sizes[i]);
            putchar('}');
        }
        puts("]}");
    }
    printf("{\"protocolVersion\":2,\"component\":\"c-binary\",\"type\":\"end\",\"sequence\":2,\"messages\":3,"
           "\"records\":%" PRIu64 "}\n",
           reader.records);
    return ok ? 0 : reader.error_code;
}
