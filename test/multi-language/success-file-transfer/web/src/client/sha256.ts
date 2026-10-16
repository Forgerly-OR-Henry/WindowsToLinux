const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);

const rotate = (value: number, bits: number) => (value >>> bits) | (value << (32 - bits));

/** Incremental SHA-256 over original file bytes; digestHex closes the input. */
export class Sha256 {
  private readonly state = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
  ]);
  private readonly block = new Uint8Array(64);
  private readonly words = new Uint32Array(64);
  private buffered = 0;
  private bytes = 0;
  private result: string | undefined;

  update(input: Uint8Array): this {
    if (this.result !== undefined) throw new Error("SHA-256 input is already closed");
    if (!Number.isSafeInteger(this.bytes + input.byteLength)) throw new RangeError("SHA-256 input is too large");
    this.bytes += input.byteLength;
    let offset = 0;
    if (this.buffered > 0) {
      const count = Math.min(64 - this.buffered, input.byteLength);
      this.block.set(input.subarray(0, count), this.buffered);
      this.buffered += count;
      offset = count;
      if (this.buffered === 64) {
        this.compress(this.block, 0);
        this.buffered = 0;
      }
    }
    while (offset + 64 <= input.byteLength) {
      this.compress(input, offset);
      offset += 64;
    }
    if (offset < input.byteLength) {
      this.block.set(input.subarray(offset));
      this.buffered = input.byteLength - offset;
    }
    return this;
  }

  digestHex(): string {
    if (this.result !== undefined) return this.result;
    this.block[this.buffered] = 0x80;
    this.block.fill(0, this.buffered + 1);
    if (this.buffered >= 56) {
      this.compress(this.block, 0);
      this.block.fill(0);
    }
    const length = new DataView(this.block.buffer);
    length.setUint32(56, Math.floor(this.bytes / 0x20000000), false);
    length.setUint32(60, (this.bytes % 0x20000000) * 8, false);
    this.compress(this.block, 0);
    this.result = Array.from(this.state, (word) => word.toString(16).padStart(8, "0")).join("");
    return this.result;
  }

  private compress(input: Uint8Array, offset: number): void {
    const w = this.words;
    for (let i = 0; i < 16; i++) {
      const j = offset + i * 4;
      w[i] = ((input[j] << 24) | (input[j + 1] << 16) | (input[j + 2] << 8) | input[j + 3]) >>> 0;
    }
    for (let i = 16; i < 64; i++) {
      const x = w[i - 15], y = w[i - 2];
      const s0 = rotate(x, 7) ^ rotate(x, 18) ^ (x >>> 3);
      const s1 = rotate(y, 17) ^ rotate(y, 19) ^ (y >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }
    let [a, b, c, d, e, f, g, h] = this.state;
    for (let i = 0; i < 64; i++) {
      const sum1 = rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25);
      const choose = (e & f) ^ (~e & g);
      const t1 = (h + sum1 + choose + K[i] + w[i]) >>> 0;
      const sum0 = rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22);
      const majority = (a & b) ^ (a & c) ^ (b & c);
      const t2 = (sum0 + majority) >>> 0;
      h = g;
      g = f;
      f = e;
      e = (d + t1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) >>> 0;
    }
    this.state[0] = (this.state[0] + a) >>> 0;
    this.state[1] = (this.state[1] + b) >>> 0;
    this.state[2] = (this.state[2] + c) >>> 0;
    this.state[3] = (this.state[3] + d) >>> 0;
    this.state[4] = (this.state[4] + e) >>> 0;
    this.state[5] = (this.state[5] + f) >>> 0;
    this.state[6] = (this.state[6] + g) >>> 0;
    this.state[7] = (this.state[7] + h) >>> 0;
  }
}

export async function sha256Blob(blob: Blob): Promise<string> {
  const hash = new Sha256();
  const chunkBytes = 1048576;
  for (let offset = 0; offset < blob.size; offset += chunkBytes) {
    hash.update(new Uint8Array(await blob.slice(offset, Math.min(offset + chunkBytes, blob.size)).arrayBuffer()));
  }
  return hash.digestHex();
}
