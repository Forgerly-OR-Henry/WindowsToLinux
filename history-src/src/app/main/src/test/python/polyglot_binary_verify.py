"""Binary fixtures built with Python struct/zlib; assertions do not use the C implementation."""

import json
import os
from pathlib import Path
import struct
import zlib
from polyglot_business_verify import check
from polyglot_native_verify import invoke, protocol_faults


def encode(blocks, records=None):
    count = sum(len(block) for block in blocks) if records is None else records
    body = bytearray(struct.pack("<4sHHIQI", b"WTL2", 2, 24, len(blocks), count, 0))
    for block in blocks:
        payload = b"".join(block)
        body.extend(struct.pack("<4sIII", b"BLK2", len(payload), len(block), zlib.crc32(payload)))
        body.extend(payload)
    return bytes(body) + struct.pack("<I", zlib.crc32(body))


def binary_inspector(r):
    p = r.work / "success-binary-inspector"
    exe = p / "cli/target/release" / ("binary-inspector.exe" if os.name == "nt" else "binary-inspector")
    count = 100000 if r.profile == "standard" else 5000
    path = r.evidence / "中文 分块数据.bin"
    block_records = 1024
    measures = []
    events = event_bytes = 0
    selected = []
    with path.open("wb") as file:
        crc = 0

        def emit(data):
            nonlocal crc
            file.write(data)
            crc = zlib.crc32(data, crc)

        emit(
            struct.pack(
                "<4sHHIQI",
                b"WTL2",
                2,
                24,
                (count + block_records - 1) // block_records,
                count,
                0,
            )
        )
        for begin in range(0, count, block_records):
            payload = bytearray()
            for n in range(begin, min(begin + block_records, count)):
                if n % 4 == 0:
                    text = f"事件 {n}".encode()
                    events += 1
                    event_bytes += len(text)
                    payload.extend(struct.pack("<BBHI", 2, n % 4, len(text) + 4, n) + text)
                else:
                    value = n % 201 - 100
                    measures.append(value)
                    payload.extend(struct.pack("<BBHIq", 1, n % 4, 12, n, value))
                    if -10 <= value <= 20:
                        selected.append(value)
            emit(
                struct.pack(
                    "<4sIII",
                    b"BLK2",
                    len(payload),
                    min(block_records, count - begin),
                    zlib.crc32(payload),
                )
            )
            emit(payload)
        file.write(struct.pack("<I", crc))
    r.scale = {
        "binaryRecords": count,
        "blocks": (count + block_records - 1) // block_records,
        "measurements": len(measures),
        "events": events,
        "jobs": 3,
    }
    command = [exe, "--input", path, "--format", "json", "--jobs", "3"]
    with check(
        r,
        "scale",
        f"{count} records streamed across blocks, two kinds and independent sums/CRC",
    ):
        report = invoke(r, command)
        result = report["items"][0]["result"]
        assert (
            result["records"] == count
            and result["blocks"] == r.scale["blocks"]
            and result["bytes"] == path.stat().st_size
        )
        assert (
            result["measurements"] == len(measures)
            and result["events"] == events
            and result["eventBytes"] == event_bytes
        )
        assert (
            result["selected"] == count
            and result["sum"] == sum(measures)
            and result["min"] == min(measures)
            and result["max"] == max(measures)
        )
        assert result["checksum"] == f"{crc:08x}" and result["flagsOr"] == 3
        assert result["eventSamples"][0] == {"id": 0, "text": "事件 0"}
        assert report["summary"]["sum"] == str(sum(measures))
    with check(
        r,
        "business",
        "type/range filters still validate complete files, empty file and batch reports",
    ):
        filtered = invoke(r, command + ["--type", "measurement", "--min", "-10", "--max", "20"])["items"][0]["result"]
        assert (
            filtered["records"] == count
            and filtered["selected"] == len(selected)
            and filtered["sum"] == sum(selected)
            and filtered["selectedEvents"] == 0
        )
        event_only = invoke(r, command + ["--type", "event"])["items"][0]["result"]
        assert event_only["selected"] == events and event_only["min"] is None and event_only["sum"] == 0
        empty = r.evidence / "empty.bin"
        empty.write_bytes(encode([]))
        assert invoke(r, [exe, "--input", empty, "--format", "json"])["items"][0]["result"]["records"] == 0
        normal = p / "samples/normal.bin"
        fixed = invoke(r, [exe, "--input", normal, "--format", "json"])["items"][0]["result"]
        assert (
            fixed["records"] == 5
            and fixed["measurements"] == 3
            and fixed["events"] == 2
            and fixed["sum"] == 7
            and fixed["min"] == -2
            and fixed["max"] == 5
        )
        output = r.evidence / "批量 报告.json"
        both = invoke(r, command + ["--input", normal, "--output", output])
        assert both["summary"]["records"] == count + 5 and both["summary"]["sum"] == str(sum(measures) + 7)
        assert json.loads(output.read_text(encoding="utf-8")) == both
        text, _ = invoke(r, [exe, "--input", normal], json_output=False)
        assert "binary-inspector" in text
    with check(
        r,
        "fault",
        "precise block/offset errors for header, types, lengths, counts, checksums and truncation",
    ):
        measurement = struct.pack("<BBHIq", 1, 0, 12, 7, -4)
        event = struct.pack("<BBHI", 2, 0, 6, 8) + b"ok"
        small = encode([[measurement], [event]])
        cases = []

        def change(name, offset, value, expected_offset, block):
            data = bytearray(small)
            data[offset : offset + len(value)] = value
            cases.append((name, bytes(data), expected_offset, block))

        change("magic", 0, b"FAIL", 0, None)
        change("version", 4, b"\x00\x02", 4, None)
        change("header-size", 6, b"\x00\x18", 6, None)
        change("block-magic", 24, b"NOPE", 24, 0)
        change("block-length", 28, struct.pack("<I", 1048577), 28, 0)
        change("block-count", 32, struct.pack("<I", 3), 28, 0)
        change("unknown-type", 40, b"\x09", 40, 0)
        change("record-length", 42, b"\xff\xff", 42, 0)
        change("flags", 41, b"\x08", 41, 0)
        change("block-crc", 36, b"\x00\x00\x00\x00", 36, 0)
        change("record-count", 12, struct.pack("<Q", 3), 12, None)
        cases.append(("truncated", small[:-2], len(small) - 2, None))
        bad = bytearray(small)
        bad[-1] ^= 1
        cases.append(("file-crc", bytes(bad), len(small) - 4, None))
        cases.append(("trailing", small + b"x", len(small), None))
        # Block 1 starts at 56; its record payload begins at 76, UTF-8 begins at 80.
        change("utf8", 80, b"\xff", 80, 1)
        for name, data, offset, block in cases:
            badpath = r.evidence / (name + ".bin")
            badpath.write_bytes(data)
            result = invoke(r, [exe, "--input", badpath, "--input", normal, "--format", "json"], 2)
            assert result["status"] == "partial" and result["items"][1]["status"] == "complete"
            error = result["items"][0]["error"]
            assert error["offset"] == offset and error["block"] == block, (name, error)
            assert result["summary"]["records"] == 5
        # Even a measurement-only query must reject malformed event payloads.
        invoke(
            r,
            [
                exe,
                "--input",
                r.evidence / "utf8.bin",
                "--type",
                "measurement",
                "--format",
                "json",
            ],
            2,
        )
        invoke(r, command + ["--max-bytes", "1024"], 2)
        missing = invoke(
            r,
            [
                exe,
                "--input",
                r.evidence / "missing.bin",
                "--input",
                normal,
                "--format",
                "json",
            ],
            3,
        )
        assert missing["summary"]["records"] == 5
        assert invoke(r, command)["summary"]["records"] == count
    with check(
        r,
        "fault",
        "protocol version/fields/end, output cap, timeout, abnormal exit and real child cleanup",
    ):
        native_command = [
            exe,
            "--input",
            p / "samples/normal.bin",
            "--format",
            "json",
            "--timeout-ms",
            "250",
        ]
        valid = [
            {
                "protocolVersion": 2,
                "component": "c-binary",
                "type": "start",
                "sequence": 0,
            },
            {
                "protocolVersion": 2,
                "component": "c-binary",
                "type": "summary",
                "sequence": 1,
                **fixed,
            },
            {
                "protocolVersion": 2,
                "component": "c-binary",
                "type": "end",
                "sequence": 2,
                "messages": 3,
                "records": 5,
            },
        ]
        protocol_faults(r, native_command, valid)
        helper = p / "native/build" / ("binary-worker.exe" if os.name == "nt" else "binary-worker")
        disabled = helper.with_suffix(".disabled")
        helper.rename(disabled)
        try:
            assert invoke(r, native_command, 4)["items"][0]["status"] == "error"
        finally:
            disabled.rename(helper)
        assert invoke(r, native_command)["items"][0]["result"]["sum"] == 7
