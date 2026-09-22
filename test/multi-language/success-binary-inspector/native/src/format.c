#include "format.h"
#include <stdlib.h>
#ifdef _WIN32
#include <windows.h>
#endif
FILE *open_utf8(const char *path) {
#ifdef _WIN32
    int n = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, NULL, 0);
    if (n <= 0)
        return NULL;
    wchar_t *wide = malloc((size_t)n * sizeof(wchar_t));
    if (!wide)
        return NULL;
    MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, wide, n);
    FILE *file = _wfopen(wide, L"rb");
    free(wide);
    return file;
#else
    return fopen(path, "rb");
#endif
}
uint16_t little16(const unsigned char *p) { return (uint16_t)(p[0] | ((uint16_t)p[1] << 8)); }
uint32_t little32(const unsigned char *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}
uint64_t little64(const unsigned char *p) { return little32(p) | ((uint64_t)little32(p + 4) << 32); }
uint32_t crc_update(uint32_t crc, const unsigned char *data, size_t size) {
    for (size_t i = 0; i < size; i++) {
        crc ^= data[i];
        for (int b = 0; b < 8; b++)
            crc = (crc >> 1) ^ ((crc & 1) ? 0xedb88320u : 0);
    }
    return crc;
}
int fail(Reader *reader, const char *message, uint64_t offset, int code) {
    reader->error = message;
    reader->error_offset = offset;
    reader->error_code = code;
    return 0;
}
int read_bytes(Reader *r, unsigned char *target, size_t size, int file_crc, int block_crc) {
    if (size > r->limit - r->offset)
        return fail(r, "file size exceeds --max-bytes", r->offset, 2);
    size_t n = fread(target, 1, size, r->file);
    uint64_t offset = r->offset;
    r->offset += n;
    if (n != size)
        return fail(r, ferror(r->file) ? "file read error" : "truncated input", offset + n, ferror(r->file) ? 3 : 2);
    if (file_crc)
        r->file_crc = crc_update(r->file_crc, target, size);
    if (block_crc)
        r->block_crc = crc_update(r->block_crc, target, size);
    return 1;
}
int valid_utf8(const unsigned char *s, size_t size) {
    size_t i = 0;
    while (i < size) {
        uint32_t value = s[i++];
        unsigned extra = 0;
        if (value < 0x80)
            continue;
        if (value >= 0xc2 && value <= 0xdf) {
            extra = 1;
            value &= 0x1f;
        } else if (value >= 0xe0 && value <= 0xef) {
            extra = 2;
            value &= 0x0f;
        } else if (value >= 0xf0 && value <= 0xf4) {
            extra = 3;
            value &= 7;
        } else
            return 0;
        if (size - i < extra)
            return 0;
        for (unsigned n = 0; n < extra; n++) {
            if ((s[i] & 0xc0) != 0x80)
                return 0;
            value = (value << 6) | (s[i++] & 0x3f);
        }
        if ((extra == 1 && value < 0x80) || (extra == 2 && value < 0x800) || (extra == 3 && value < 0x10000) ||
            value > 0x10ffff || (value >= 0xd800 && value <= 0xdfff))
            return 0;
    }
    return 1;
}
void json_string(const unsigned char *text, size_t size) {
    putchar('"');
    for (size_t n = 0; n < size; n++) {
        unsigned char c = text[n];
        if (c == '"' || c == '\\') {
            putchar('\\');
            putchar(c);
        } else if (c < 32)
            printf("\\u%04x", c);
        else
            putchar(c);
    }
    putchar('"');
}
