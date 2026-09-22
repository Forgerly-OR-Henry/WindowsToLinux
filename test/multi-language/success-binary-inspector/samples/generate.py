"""Generate WTL2 samples without retaining a whole large file in memory."""

import argparse
from pathlib import Path
import struct
import zlib


def measurement(id, value, flags=0):
    return struct.pack("<BBHIq", 1, flags, 12, id, value)


def event(id, text, flags=0):
    data = text.encode("utf-8")
    if len(data) > 4096:
        raise ValueError("event text exceeds 4096 bytes")
    return struct.pack("<BBHI", 2, flags, len(data) + 4, id) + data


def write(path, count, records, block_records=1024):
    blocks = (count + block_records - 1) // block_records
    with path.open("wb") as file:
        crc = 0

        def emit(data):
            nonlocal crc
            file.write(data)
            crc = zlib.crc32(data, crc)

        emit(struct.pack("<4sHHIQI", b"WTL2", 2, 24, blocks, count, 0))
        iterator = iter(records)
        for block in range(blocks):
            n = min(block_records, count - block * block_records)
            payload = b"".join(next(iterator) for _ in range(n))
            if len(payload) > 1048576:
                raise ValueError("block exceeds 1 MiB")
            emit(struct.pack("<4sIII", b"BLK2", len(payload), n, zlib.crc32(payload)))
            emit(payload)
        file.write(struct.pack("<I", crc))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--records", type=int)
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    if args.records is not None:
        if not 1 <= args.records <= 1000000:
            parser.error("--records must be 1..1000000")
        write(
            args.output_dir / "large.bin",
            args.records,
            (
                (event(i, f"事件 {i}", i % 4) if i % 4 == 0 else measurement(i, i % 201 - 100, i % 4))
                for i in range(args.records)
            ),
        )
    else:
        write(
            args.output_dir / "normal.bin",
            5,
            [
                measurement(1, 4, 1),
                event(2, "启动", 2),
                measurement(3, -2),
                event(4, "完成"),
                measurement(5, 5, 3),
            ],
            3,
        )
        write(args.output_dir / "empty.bin", 0, [])
        normal = (args.output_dir / "normal.bin").read_bytes()
        (args.output_dir / "truncated.bin").write_bytes(normal[:-3])
        corrupt = bytearray(normal)
        corrupt[48] ^= 1
        (args.output_dir / "corrupt.bin").write_bytes(corrupt)
