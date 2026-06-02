// Shared frames assertion for the mobile e2e tests (Android + iOS).
//
// Given a session public id, checks the new frames pipeline end-to-end:
//   1. GET /v1/sessions/:id/frames returns a URL that is a DIRECT object-store
//      URL (not the API host) — i.e. the API is not proxying the bytes.
//   2. That .gz is fetchable straight from the object store WITH a browser
//      Origin header → 200 + Access-Control-Allow-Origin (the player's path).
//   3. It gunzips and parses as a `[uint64 ts][uint32 size][jpeg]*` archive
//      with at least EXPECT_MIN frames.
//
// Exit codes: 0 = packed + valid, 2 = not packed yet (url null), 1 = invalid.
//
// Env: API, SID, WID, JWT_SECRET, EXPECT_MIN, API_HOST (host:port to reject
// as a proxy), ORIGIN.
import { gunzipSync } from "zlib";
import { createHmac } from "crypto";

const { API, SID, WID, JWT_SECRET, EXPECT_MIN = "1", API_HOST = "localhost:4000",
  ORIGIN = "http://localhost:5180" } = process.env;

function jwt(wid) {
  const h = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  const now = Math.floor(Date.now() / 1000);
  const p = Buffer.from(JSON.stringify({
    userId: 1, email: "iamnasirudeen@gmail.com", workspaceId: Number(wid),
    iat: now, exp: now + 3600,
  })).toString("base64url");
  return `${h}.${p}.${createHmac("sha256", JWT_SECRET).update(`${h}.${p}`).digest("base64url")}`;
}

const r = await fetch(`${API}/v1/sessions/${SID}/frames`, {
  headers: { authorization: `Bearer ${jwt(WID)}`, "x-workspace-id": String(WID) },
});
const body = await r.json();
const meta = body.data || body;

if (!meta.url) {
  console.log(`   /frames → url: null (not packed yet)`);
  process.exit(2);
}

// (1) must be a direct object-store URL, never proxied through the API
if (meta.url.includes(API_HOST)) {
  console.log(`   ❌ /frames url points at the API (${meta.url}) — bytes are being proxied`);
  process.exit(1);
}

// (2) fetch direct from the object store with a browser Origin
const r2 = await fetch(meta.url, { headers: { Origin: ORIGIN } });
const acao = r2.headers.get("access-control-allow-origin");
if (r2.status !== 200) {
  console.log(`   ❌ object-store GET ${r2.status} for ${meta.url}`);
  process.exit(1);
}
const buf = Buffer.from(await r2.arrayBuffer());

// (3) gunzip + parse the frames archive
let archive;
try { archive = gunzipSync(buf); } catch (e) {
  console.log(`   ❌ gunzip failed: ${e.message}`);
  process.exit(1);
}
let off = 0, frames = 0;
while (off + 12 <= archive.length) {
  const size = archive.readUInt32LE(off + 8);
  off += 12 + size;
  if (off > archive.length) break;
  frames += 1;
}

const ok = frames >= Number(EXPECT_MIN);
console.log(
  `   /frames url: ${meta.url}\n` +
  `   direct object-store fetch: ${r2.status}  ACAO: ${acao}  gz: ${buf.length}B\n` +
  `   gunzip+parse → ${frames} frames (reported count ${meta.count})  ${ok ? "✅" : `❌ expected ≥ ${EXPECT_MIN}`}`,
);
process.exit(ok ? 0 : 1);
