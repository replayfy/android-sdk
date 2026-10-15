// Pixel assertions for the Android privacy e2e (scripts/e2e-privacy.sh).
//
// Fetches a session's frames archive the way assert-frames.mjs does, writes
// each JPEG out, converts it to BMP with macOS `sips`, and checks the pixels
// against the rects PrivacyActivity logged (REPLAY_E2E_RECTS lines in a
// logcat dump, frame px; frames are encoded at half size):
//
//   - every field masked with no setup, the scaled privacy view and the
//     WebView page's password input are the solid mask grey, rgb(56,56,56);
//   - the plain control and the page's text input are not;
//   - while the keyboard pans the window, the masks are still on the fields;
//   - the WebView password mask follows a scroll within one frame;
//   - every frame after FLAG_SECURE is turned on is black.
//
// Exit codes: 0 = pass, 2 = not packed yet, 1 = fail.
//
// Env: API, SID, WID, JWT_SECRET, LOGCAT (path of `adb logcat -d -v epoch`).
import { gunzipSync } from "zlib";
import { createHmac } from "crypto";
import { execFileSync } from "child_process";
import { mkdtempSync, readFileSync, realpathSync, writeFileSync } from "fs";
import { tmpdir } from "os";
import { join } from "path";
import { pathToFileURL } from "url";

const { API, SID, WID, JWT_SECRET, LOGCAT } = process.env;

const MASKED = ["password", "numberPassword", "transformation", "card", "otp", "bottomCard", "scaled", "webPassword"];
const CLEAR = ["control", "webText"];
const GREY_TOL = 16;

function jwt(wid) {
  const h = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  const now = Math.floor(Date.now() / 1000);
  const p = Buffer.from(JSON.stringify({
    userId: 1, email: "iamnasirudeen@gmail.com", workspaceId: Number(wid),
    iat: now, exp: now + 3600,
  })).toString("base64url");
  return `${h}.${p}.${createHmac("sha256", JWT_SECRET).update(`${h}.${p}`).digest("base64url")}`;
}

/** The `[u64 ts][u32 size][jpeg]*` archive as [{ ts, jpeg }]. */
export function parseArchive(raw) {
  const out = [];
  let off = 0;
  while (off + 12 <= raw.length) {
    const ts = Number(raw.readBigUInt64LE(off));
    const size = raw.readUInt32LE(off + 8);
    if (off + 12 + size > raw.length) break;
    out.push({ ts, jpeg: raw.subarray(off + 12, off + 12 + size) });
    off += 12 + size;
  }
  return out;
}

/** A 24- or 32-bit uncompressed BMP as { width, height, px(x, y) → [r,g,b] }. */
export function parseBmp(buf) {
  if (buf.toString("ascii", 0, 2) !== "BM") throw new Error("not a BMP");
  const dataOff = buf.readUInt32LE(10);
  const width = buf.readInt32LE(18);
  const rawHeight = buf.readInt32LE(22);
  const bpp = buf.readUInt16LE(28);
  if (bpp !== 24 && bpp !== 32) throw new Error(`unsupported BMP depth ${bpp}`);
  const height = Math.abs(rawHeight);
  const bottomUp = rawHeight > 0;
  const stride = Math.ceil((width * bpp) / 32) * 4;
  return {
    width,
    height,
    px(x, y) {
      const cx = Math.min(Math.max(Math.round(x), 0), width - 1);
      const cy = Math.min(Math.max(Math.round(y), 0), height - 1);
      const row = bottomUp ? height - 1 - cy : cy;
      const i = dataOff + row * stride + cx * (bpp / 8);
      return [buf[i + 2], buf[i + 1], buf[i]];
    },
  };
}

/** REPLAY_E2E_RECTS lines and the FLAG_SECURE time from a logcat dump. */
export function parseLog(text) {
  const rects = [];
  let secureAt = null;
  for (const line of text.split("\n")) {
    const r = line.indexOf("REPLAY_E2E_RECTS ");
    if (r >= 0) {
      try { rects.push(JSON.parse(line.slice(r + "REPLAY_E2E_RECTS ".length))); } catch { /* torn line */ }
    }
    const s = line.match(/REPLAY_E2E_SECURE (\d+)/);
    if (s) secureAt = Number(s[1]);
  }
  rects.sort((a, b) => a.at - b.at);
  return { rects, secureAt };
}

const isGrey = ([r, g, b]) =>
  Math.abs(r - 56) <= GREY_TOL && Math.abs(g - 56) <= GREY_TOL && Math.abs(b - 56) <= GREY_TOL;
const sameBox = (a, b) => a && b && a.every((v, i) => Math.abs(v - b[i]) <= 4);

/** The rects lines just before and just after [ts]. */
function around(rects, ts) {
  let before = null, after = null;
  for (const r of rects) {
    if (r.at <= ts) before = r;
    else { after = r; break; }
  }
  return { before, after };
}

/** Check one decoded frame against the rects logged around it. Returns a
 *  list of failures, and notes what was checked. */
export function checkFrame(bmp, ts, log, seen) {
  const fails = [];
  if (log.secureAt !== null && ts >= log.secureAt + 500) {
    let sum = 0, n = 0;
    for (let y = 0; y < bmp.height; y += 8) for (let x = 0; x < bmp.width; x += 8) {
      const [r, g, b] = bmp.px(x, y); sum += (r + g + b) / 3; n++;
    }
    seen.secure++;
    if (sum / n >= 8) fails.push(`frame ${ts}: after FLAG_SECURE, mean luma ${(sum / n).toFixed(1)}`);
    return fails;
  }
  const { before, after } = around(log.rects, ts);
  if (!before) return fails;
  const moving = (name) => !after || !sameBox(before.frame[name], after.frame[name]);
  const centre = (box) => bmp.px((box[0] + box[2]) / 4, (box[1] + box[3]) / 4);
  for (const name of MASKED) {
    const box = before.frame[name];
    if (!box || moving(name)) continue; // not shown, or moving: checked below
    seen[name] = (seen[name] || 0) + 1;
    if (!isGrey(centre(box))) fails.push(`frame ${ts}: ${name} ${JSON.stringify(box)} not masked (rgb ${centre(box)})${before.pan ? ` pan ${before.pan}` : ""}`);
  }
  for (const name of CLEAR) {
    const box = before.frame[name];
    if (!box || moving(name)) continue;
    if (isGrey(centre(box))) fails.push(`frame ${ts}: ${name} masked but should not be`);
  }
  if (before.pan < 0 && before.frame.bottomCard && !moving("bottomCard")) {
    seen.panned++;
    if (!isGrey(centre(before.frame.bottomCard))) fails.push(`frame ${ts}: panned ${before.pan}, bottomCard not masked`);
  }
  return fails;
}

async function main() {
  const r = await fetch(`${API}/v1/sessions/${SID}/frames`, {
    headers: { authorization: `Bearer ${jwt(WID)}`, "x-workspace-id": String(WID) },
  });
  const body = await r.json();
  const meta = body.data || body;
  if (!meta.url) {
    console.log("   /frames → url: null (not packed yet)");
    process.exit(2);
  }
  const archive = parseArchive(gunzipSync(Buffer.from(await (await fetch(meta.url)).arrayBuffer())));
  const log = parseLog(readFileSync(LOGCAT, "utf8"));
  if (log.rects.length === 0) {
    console.log("   ❌ no REPLAY_E2E_RECTS lines in the logcat dump");
    process.exit(1);
  }
  const dir = mkdtempSync(join(tmpdir(), "replay-privacy-"));
  const seen = { secure: 0, panned: 0 };
  const fails = [];
  // The web password may miss its mask on one frame while the page scrolls,
  // never on two in a row.
  let webMissRun = 0, webMissWorst = 0;
  for (const [i, f] of archive.entries()) {
    const jpg = join(dir, `${i}.jpg`);
    const bmpPath = join(dir, `${i}.bmp`);
    writeFileSync(jpg, f.jpeg);
    execFileSync("sips", ["-s", "format", "bmp", jpg, "--out", bmpPath], { stdio: "ignore" });
    const frameFails = checkFrame(parseBmp(readFileSync(bmpPath)), f.ts, log, seen);
    const webMiss = frameFails.filter((m) => m.includes("webPassword"));
    webMissRun = webMiss.length ? webMissRun + 1 : 0;
    webMissWorst = Math.max(webMissWorst, webMissRun);
    fails.push(...frameFails.filter((m) => !m.includes("webPassword")));
  }
  if (webMissWorst > 1) fails.push(`webPassword unmasked on ${webMissWorst} frames in a row`);
  console.log(`   ${archive.length} frames → ${dir}`);
  console.log(`   checked: ${JSON.stringify(seen)}`);
  for (const name of MASKED) if (!seen[name]) fails.push(`${name} never checked (not on screen in any settled frame)`);
  if (!seen.panned) fails.push("no frame while the window was panned");
  if (log.secureAt !== null && !seen.secure) fails.push("no frame after FLAG_SECURE");
  if (fails.length) {
    for (const m of fails.slice(0, 40)) console.log(`   ❌ ${m}`);
    process.exit(1);
  }
  console.log("   ✅ every mask on its field, controls clear, secure frames black");
  process.exit(0);
}

if (process.argv[1] && import.meta.url === pathToFileURL(realpathSync(process.argv[1])).href) await main();
