// Unit-Tests fuer die webfetch-Allowlist in start.mjs und den Egress-Filter
// (egress.mjs): node --test infra/opencode/webfetch.test.mjs
// (laeuft auch ueber pytest: tests/test_start_script.py)
import assert from "node:assert/strict";
import fs from "node:fs";
import http from "node:http";
import { test } from "node:test";

import { allowedTargets, bypass, createGuard, key, startGuard, target, upstream } from "./egress.mjs";
import { environment, normalizeWebfetchEntry, parseJsonc, parseWebfetchAllow, webfetchPatterns, webfetchPolicies } from "./start.mjs";

const CASES = JSON.parse(fs.readFileSync(new URL("../../tests/data/webfetch_allow.json", import.meta.url), "utf8"));
const BASE = parseJsonc(fs.readFileSync(new URL("./config/base.jsonc", import.meta.url), "utf8"));

// Nachbau von opencode 2.0.20 (Funktion JD und Policy-Hook, aus dem Binary)
function wildcard(value, pattern) {
  const v = value.replaceAll("\\", "/");
  let rx = pattern.replaceAll("\\", "/").replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*").replace(/\?/g, ".");
  if (rx.endsWith(" .*")) rx = rx.slice(0, -3) + "( .*)?";
  return new RegExp("^" + rx + "$", "s").test(v);
}
// Quellen in Ladereihenfolge; opencode dreht sie um und nimmt den LETZTEN
// Treffer -> die frueheste Quelle (Sicherheitsbasis) gewinnt.
function blocked(sources, action, resource) {
  const list = [...sources].reverse().flatMap((s) => s.experimental?.policies ?? []);
  const hit = list.findLast((p) => p.action === "permission" && wildcard(`${action}:${resource}`, p.resource));
  return hit?.effect === "deny";
}
const content = (env) => JSON.parse(env.OPENCODE_CONFIG_CONTENT);

test("Normalisierung: gemeinsame Testfaelle (wie captain/webfetch.py)", () => {
  for (const c of CASES.valid) assert.deepEqual(parseWebfetchAllow(c.input), c.prefixes, c.input);
  for (const entry of CASES.invalid) {
    assert.throws(() => parseWebfetchAllow(entry), /CAPTAIN_WEBFETCH_ALLOW/, entry);
    assert.throws(() => parseWebfetchAllow(`http://ok.example, ${entry}`), undefined, entry);
  }
  assert.equal(normalizeWebfetchEntry("HTTPS://A.Example:443/X/"), "https://a.example/X");
});

test("Sicherheitsbasis enthaelt keine webfetch-Policy (sonst gewaenne sie gegen jede Freigabe)", () => {
  const policies = BASE.experimental.policies.map((p) => p.resource);
  assert.ok(!policies.some((r) => r.startsWith("webfetch")), policies);
  for (const r of ["shell:*", "websearch:*", "subagent:*", "skill:*", "question:*", "external_directory:*", "opencode_*"]) {
    assert.ok(policies.includes(r), r);
  }
});

test("Policies: Allowlist als Obergrenze, Angriffsmuster abgelehnt", () => {
  for (const group of CASES.match) {
    const prefixes = parseWebfetchAllow(group.allow);
    assert.deepEqual(webfetchPatterns(prefixes), group.patterns);
    const env = environment({}, ["llm"], { webfetch: prefixes });
    // Admin-Config mit eigenen allow-Policies: verwirft start.mjs; selbst wenn
    // nicht, kaemen sie NACH der Basis und VOR OPENCODE_CONFIG_CONTENT.
    const sources = [BASE, {}, content(env)];
    for (const [url, allowed] of group.cases) {
      assert.equal(blocked(sources, "webfetch", url), !allowed, url);
    }
    assert.ok(blocked(sources, "shell", "id") && blocked(sources, "websearch", "x"));
  }
});

test("Policies ohne Allowlist: webfetch komplett gesperrt", () => {
  assert.deepEqual(webfetchPolicies([]), [{ action: "permission", resource: "webfetch:*", effect: "deny" }]);
  const env = environment({}, ["llm"]);
  assert.deepEqual(content(env).experimental.policies, webfetchPolicies([]));
  assert.ok(blocked([BASE, {}, content(env)], "webfetch", "http://text-example.org/"));
});

test("Umgebung: Proxy nur mit Egress-Filter umgelenkt, Variable selbst nicht an opencode", () => {
  const env0 = { PATH: "/bin", HTTP_PROXY: "http://firma:3128", HTTPS_PROXY: "http://firma:3128", NO_PROXY: "llm.intern",
    CAPTAIN_WEBFETCH_ALLOW: "http://text-example.org" };
  const plain = environment(env0, ["llm"]);
  assert.equal(plain.HTTP_PROXY, "http://firma:3128");
  assert.equal(plain.NO_PROXY, "llm.intern");
  assert.equal(plain.CAPTAIN_WEBFETCH_ALLOW, undefined);
  const guarded = environment(env0, ["llm"], { webfetch: ["http://text-example.org"], proxy: "http://127.0.0.1:9" });
  for (const k of ["HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"]) assert.equal(guarded[k], "http://127.0.0.1:9", k);
  assert.equal(guarded.NO_PROXY, "localhost,127.0.0.1,::1");
  assert.equal(guarded.no_proxy, "localhost,127.0.0.1,::1");
  assert.equal(guarded.CAPTAIN_WEBFETCH_ALLOW, undefined);
  assert.equal(content(guarded).share, "disabled");
});

test("Egress: erlaubte Ziele aus Allowlist und Infrastruktur", () => {
  const warnings = [];
  const clean = {
    providers: { llm: { settings: { baseURL: "https://vllm.example.org/v1" } }, ollama: { settings: { baseURL: "http://host.docker.internal:11434/v1" } },
      anthropic: {}, kaputt: {} },
    mcp: { servers: { wetter: { url: "https://mcp.example.org:8443/mcp" } } },
  };
  const set = allowedTargets(["http://text-example.org", "https://docs.example.org:8443/x"], clean,
    { ANTHROPIC_API_KEY: "a", LLM_BASE_URL: "http://llm.intern:8000/v1" }, (w) => warnings.push(w));
  assert.deepEqual([...set].sort(), [
    "api.anthropic.com:443", "docs.example.org:8443", "host.docker.internal:11434", "llm.intern:8000",
    "mcp.example.org:8443", "text-example.org:443", "text-example.org:80", "vllm.example.org:443",
  ]);
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /providers\.kaputt/);
  assert.equal(target("http://[::1]:8080/x"), "::1:8080");
  assert.equal(target("kein url"), null);
  assert.equal(key("Text-Example.ORG", "80"), "text-example.org:80");
});

test("Egress: NO_PROXY und Upstream-Proxy", () => {
  assert.ok(bypass("llm.intern", 443, "a.org, llm.intern"));
  assert.ok(bypass("x.firma.intern", 443, ".firma.intern"));
  assert.ok(bypass("x.firma.intern", 443, "firma.intern"));
  assert.ok(!bypass("evilfirma.intern", 443, "firma.intern"));
  assert.ok(bypass("a.org", 8080, "a.org:8080") && !bypass("a.org", 443, "a.org:8080"));
  assert.ok(bypass("irgendwas", 1, "*"));
  assert.ok(!bypass("a.org", 80, ""));
  assert.equal(upstream("http", {}), null);
  assert.equal(upstream("connect", { HTTP_PROXY: "http://p:1" }).host, "p:1");
  assert.equal(upstream("connect", { HTTPS_PROXY: "http://s:2", HTTP_PROXY: "http://p:1" }).host, "s:2");
  assert.throws(() => createGuard({ allowed: new Set(), env: { HTTPS_PROXY: "https://p:1" } }), /nur http/);
});

// --- Verhalten des Filters mit echten Sockets -------------------------------

function stop(...servers) {
  for (const s of servers) {
    s.close();
    s.closeAllConnections?.();
  }
}

function listen(server) {
  return new Promise((resolve) => server.listen(0, "127.0.0.1", () => resolve(server.address().port)));
}

function origin() {
  const seen = [];
  const server = http.createServer((req, res) => {
    seen.push(`${req.headers.host} ${req.url}`);
    res.writeHead(200, { "content-type": "text/plain" }).end(`ORIGIN ${req.url}`);
  });
  return { server, seen };
}

// Anfrage in Proxy-Form (absolute URL) -> { status, body }
function viaProxy(proxyPort, url) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    http.get({ host: "127.0.0.1", port: proxyPort, path: url, headers: { host: u.host }, agent: false }, (res) => {
      let body = "";
      res.on("data", (d) => (body += d));
      res.on("end", () => resolve({ status: res.statusCode, body }));
    }).on("error", reject);
  });
}

// CONNECT host:port, dann HTTP durch den Tunnel -> { status, body }
function viaConnect(proxyPort, authority, path = "/tunnel") {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: "127.0.0.1", port: proxyPort, method: "CONNECT", path: authority, agent: false });
    req.on("connect", (res, sock) => {
      if (res.statusCode !== 200) { sock.destroy(); resolve({ status: res.statusCode, body: "" }); return; }
      let data = "";
      sock.on("data", (d) => (data += d));
      sock.on("end", () => resolve({ status: 200, body: data }));
      sock.write(`GET ${path} HTTP/1.1\r\nHost: ${authority}\r\nConnection: close\r\n\r\n`);
    });
    req.on("error", reject);
    req.end();
  });
}

test("Egress: erlaubtes Ziel geht durch, fremdes nicht (HTTP und CONNECT)", async () => {
  const o = origin();
  const port = await listen(o.server);
  const logs = [];
  const { server: guard, url } = await startGuard({ allowed: new Set([key("127.0.0.1", port)]), log: (m) => logs.push(m) });
  const gport = Number(new URL(url).port);
  try {
    const ok = await viaProxy(gport, `http://127.0.0.1:${port}/hallo?x=1`);
    assert.equal(ok.status, 200);
    assert.equal(ok.body, "ORIGIN /hallo?x=1");
    const denied = await viaProxy(gport, `http://localhost:${port}/geheim`);
    assert.equal(denied.status, 403);
    const tunnel = await viaConnect(gport, `127.0.0.1:${port}`);
    assert.match(tunnel.body, /ORIGIN \/tunnel/);
    const noTunnel = await viaConnect(gport, `localhost:${port}`);
    assert.equal(noTunnel.status, 403);
    const direct = await new Promise((resolve) => http.get(`http://127.0.0.1:${gport}/x`, { agent: false }, (r) => { r.resume(); resolve(r.statusCode); }));
    assert.equal(direct, 400); // keine Proxy-Anfrage
    assert.deepEqual(o.seen, [`127.0.0.1:${port} /hallo?x=1`, `127.0.0.1:${port} /tunnel`]);
    assert.ok(logs.some((l) => l.includes(`localhost:${port}`)), logs);
  } finally {
    stop(guard, o.server);
  }
});

test("Egress: Weiterleitung ueber einen Firmen-Proxy (Upstream), NO_PROXY direkt", async () => {
  const o = origin();
  const port = await listen(o.server);
  // "Firmen-Proxy": zweiter Filter, merkt sich, was durch ihn ging
  const via = [];
  const corp = createGuard({ allowed: new Set([key("127.0.0.1", port)]) });
  corp.prependListener("request", (req) => via.push(`GET ${req.url}`));
  corp.prependListener("connect", (req) => via.push(`CONNECT ${req.url}`));
  const cport = await listen(corp);
  const env = { HTTP_PROXY: `http://127.0.0.1:${cport}`, HTTPS_PROXY: `http://127.0.0.1:${cport}` };
  const guard = createGuard({ allowed: new Set([key("127.0.0.1", port)]), env });
  const gport = await listen(guard);
  const direct = createGuard({ allowed: new Set([key("127.0.0.1", port)]), env: { ...env, NO_PROXY: "127.0.0.1" } });
  const dport = await listen(direct);
  try {
    assert.equal((await viaProxy(gport, `http://127.0.0.1:${port}/a`)).body, "ORIGIN /a");
    assert.match((await viaConnect(gport, `127.0.0.1:${port}`, "/b")).body, /ORIGIN \/b/);
    assert.deepEqual(via, [`GET http://127.0.0.1:${port}/a`, `CONNECT 127.0.0.1:${port}`]);
    via.length = 0;
    assert.equal((await viaProxy(dport, `http://127.0.0.1:${port}/c`)).body, "ORIGIN /c");
    assert.deepEqual(via, []);
  } finally {
    stop(guard, direct, corp, o.server);
  }
});

test("Egress: unerreichbares erlaubtes Ziel -> 502, kein Absturz", async () => {
  const { server: guard, url } = await startGuard({ allowed: new Set([key("127.0.0.1", 1)]) });
  try {
    const gport = Number(new URL(url).port);
    assert.equal((await viaProxy(gport, "http://127.0.0.1:1/x")).status, 502);
    assert.equal((await viaConnect(gport, "127.0.0.1:1")).status, 502);
  } finally {
    stop(guard);
  }
});
