// One-off: convert the 24-bit (RGB) icon PNG to 32-bit (RGBA, opaque alpha) to meet Play's
// "app icon = 32-bit PNG with alpha" spec. Pure Node (zlib), no native deps.
const fs = require('fs'), zlib = require('zlib');
const path = process.argv[2] || 'docs/play-store/icon-512.png';
const buf = fs.readFileSync(path);

let pos = 8, ihdr = null; const idat = [];
while (pos < buf.length) {
  const len = buf.readUInt32BE(pos);
  const type = buf.toString('ascii', pos + 4, pos + 8);
  const data = buf.slice(pos + 8, pos + 8 + len);
  if (type === 'IHDR') ihdr = data;
  if (type === 'IDAT') idat.push(data);
  pos += 12 + len;
  if (type === 'IEND') break;
}
const w = ihdr.readUInt32BE(0), h = ihdr.readUInt32BE(4);
const bitDepth = ihdr.readUInt8(8), colorType = ihdr.readUInt8(9), interlace = ihdr.readUInt8(12);
if (colorType === 6) { console.log('already RGBA — nothing to do'); process.exit(0); }
if (bitDepth !== 8 || colorType !== 2 || interlace !== 0) {
  console.error('unexpected PNG format: bitDepth', bitDepth, 'colorType', colorType, 'interlace', interlace);
  process.exit(1);
}
const raw = zlib.inflateSync(Buffer.concat(idat));
const bpp = 3, stride = w * bpp;
const rgb = Buffer.alloc(h * stride);
const paeth = (a, b, c) => { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); return (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c); };
let rp = 0;
for (let y = 0; y < h; y++) {
  const ft = raw[rp++];
  for (let x = 0; x < stride; x++) {
    const v = raw[rp++];
    const a = x >= bpp ? rgb[y * stride + x - bpp] : 0;
    const b = y > 0 ? rgb[(y - 1) * stride + x] : 0;
    const c = (x >= bpp && y > 0) ? rgb[(y - 1) * stride + x - bpp] : 0;
    let recon;
    switch (ft) {
      case 0: recon = v; break;
      case 1: recon = v + a; break;
      case 2: recon = v + b; break;
      case 3: recon = v + ((a + b) >> 1); break;
      case 4: recon = v + paeth(a, b, c); break;
      default: console.error('bad filter', ft); process.exit(1);
    }
    rgb[y * stride + x] = recon & 0xff;
  }
}
const ns = w * 4;
const filtered = Buffer.alloc(h * (ns + 1));
let fp = 0;
for (let y = 0; y < h; y++) {
  filtered[fp++] = 0; // filter: none
  for (let x = 0; x < w; x++) {
    const si = y * stride + x * 3;
    filtered[fp++] = rgb[si]; filtered[fp++] = rgb[si + 1]; filtered[fp++] = rgb[si + 2]; filtered[fp++] = 255;
  }
}
const idatNew = zlib.deflateSync(filtered, { level: 9 });
const crcTable = (() => { const t = []; for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1); t[n] = c >>> 0; } return t; })();
const crc32 = (b) => { let c = 0xffffffff; for (let i = 0; i < b.length; i++) c = crcTable[(c ^ b[i]) & 0xff] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
const chunk = (type, data) => { const len = Buffer.alloc(4); len.writeUInt32BE(data.length); const t = Buffer.from(type, 'ascii'); const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data]))); return Buffer.concat([len, t, data, crc]); };
const nih = Buffer.from(ihdr); nih.writeUInt8(6, 9); // colorType -> RGBA
const sig = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const png = Buffer.concat([sig, chunk('IHDR', nih), chunk('IDAT', idatNew), chunk('IEND', Buffer.alloc(0))]);
fs.writeFileSync(path, png);
console.log('wrote RGBA: colorType', png.readUInt8(25), '|', w + 'x' + h, '|', Math.round(png.length / 1024) + 'KB');
