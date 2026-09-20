#ifndef WTL_FORMAT_H
#define WTL_FORMAT_H
#include <stdint.h>
#include <stdio.h>
typedef struct {
    FILE *file;
    uint64_t offset, limit, records;
    uint32_t file_crc, block_crc, block;
    const char *error;
    uint64_t error_offset;
    int error_code;
} Reader;
uint16_t little16(const unsigned char *p);
uint32_t little32(const unsigned char *p);
uint64_t little64(const unsigned char *p);
uint32_t crc_update(uint32_t crc, const unsigned char *data, size_t size);
int read_bytes(Reader *reader, unsigned char *target, size_t size, int file_crc, int block_crc);
int fail(Reader *reader, const char *message, uint64_t offset, int code);
int valid_utf8(const unsigned char *text, size_t size);
FILE *open_utf8(const char *path);
void json_string(const unsigned char *text, size_t size);
#endif
