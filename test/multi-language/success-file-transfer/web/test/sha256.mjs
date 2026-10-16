import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { Sha256, sha256Blob } from "../dist/public/sha256.js";

const oracle = (bytes) => createHash("sha256").update(bytes).digest("hex");
const patterned = (size) => Uint8Array.from({ length: size }, (_, i) => (i * 73 + 19) & 255);
const mib = 1048576;

for (const [name, bytes, known] of [
  ["empty", new Uint8Array(), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"],
  ["abc", new TextEncoder().encode("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"],
  ["Unicode and NUL bytes", new TextEncoder().encode("中文文件\0🙂\r\n")],
  ["all byte values", Uint8Array.from({ length: 256 }, (_, i) => i)],
]) {
  test(`original bytes: ${name}`, async () => {
    const expected = oracle(bytes);
    if (known !== undefined) assert.equal(expected, known);
    assert.equal(new Sha256().update(bytes).digestHex(), expected);
    assert.equal(await sha256Blob(new Blob([bytes])), expected);
  });
}

for (const size of [55, 56, 63, 64, 65, 127, 128, 129]) {
  test(`SHA-256 padding boundary: ${size} bytes`, () => {
    const bytes = patterned(size);
    assert.equal(new Sha256().update(bytes).digestHex(), oracle(bytes));
  });
}

test("incremental partitions preserve the complete digest", () => {
  const bytes = patterned(8197);
  const expected = oracle(bytes);
  for (const size of [1, 7, 63, 64, 65, 257, 1024]) {
    const hash = new Sha256();
    for (let offset = 0; offset < bytes.length; offset += size) {
      hash.update(new Uint8Array());
      hash.update(bytes.subarray(offset, Math.min(offset + size, bytes.length)));
    }
    assert.equal(hash.digestHex(), expected, `partition size ${size}`);
  }
});

test("nonzero-offset views hash only the selected bytes and observe byte changes", () => {
  const backing = patterned(311);
  const bytes = backing.subarray(13, 285);
  const expected = oracle(bytes);
  assert.equal(new Sha256().update(bytes).digestHex(), expected);
  const changed = bytes.slice();
  changed[137] ^= 1;
  const actual = new Sha256().update(changed).digestHex();
  assert.equal(actual, oracle(changed));
  assert.notEqual(actual, expected);
});

test("finishing is repeatable and rejects any later input", () => {
  const hash = new Sha256().update(patterned(77));
  const first = hash.digestHex();
  assert.equal(hash.digestHex(), first);
  assert.throws(() => hash.update(new Uint8Array()), /already closed/);
  assert.throws(() => hash.update(new Uint8Array([1])), /already closed/);
});

class ObservedBlob extends Blob {
  reads = [];

  arrayBuffer() {
    throw new Error("Whole-file arrayBuffer must not be used");
  }

  slice(start, end, type) {
    const part = super.slice(start, end, type);
    this.reads.push({ start, end, bytes: part.size });
    return part;
  }
}

test("Blob hashing crosses multiple 1 MiB boundaries with bounded reads", async () => {
  const bytes = patterned(2 * mib + 129);
  const blob = new ObservedBlob([bytes]);
  assert.equal(await sha256Blob(blob), oracle(bytes));
  assert.deepEqual(blob.reads, [
    { start: 0, end: mib, bytes: mib },
    { start: mib, end: 2 * mib, bytes: mib },
    { start: 2 * mib, end: bytes.length, bytes: 129 },
  ]);
});

test("64 MiB complete Blob matches an independently streamed native digest", async () => {
  const block = patterned(mib);
  const native = createHash("sha256");
  for (let i = 0; i < 64; i++) native.update(block);
  const blob = new ObservedBlob(Array.from({ length: 64 }, () => block));
  assert.equal(await sha256Blob(blob), native.digest("hex"));
  assert.equal(blob.size, 64 * mib);
  assert.equal(blob.reads.length, 64);
  for (let i = 0; i < blob.reads.length; i++) {
    assert.deepEqual(blob.reads[i], { start: i * mib, end: (i + 1) * mib, bytes: mib });
  }
});
