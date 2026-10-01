// Egress-Filter fuer opencode – nur aktiv, wenn CAPTAIN_WEBFETCH_ALLOW gesetzt ist.
//
// opencode 2.0.20 prueft bei webfetch nur die URL, die das Modell angibt;
// Weiterleitungen folgt es selbst (fetch, redirect "follow") OHNE erneute
// Pruefung (belegt: tests/test_webfetch_integration.py). Ein erlaubter Host
// mit offener Weiterleitung waere sonst ein Tor zu jedem anderen Ziel.
//
// Deshalb laeuft der gesamte ausgehende Verkehr von opencode ueber diesen
// kleinen HTTP-Proxy im Startskript (HTTP_PROXY/HTTPS_PROXY von opencode =
// 127.0.0.1:<port>). Er laesst nur Ziele (Host:Port) durch, die opencode
// braucht oder darf:
//   - die Hosts der Allowlist (bei Standardport auch der andere Standardport,
//     damit http -> https auf demselben Host funktioniert),
//   - die Endpunkte aus der Admin-Config (providers.*.settings.baseURL,
//     mcp.servers.*.url), LLM_BASE_URL/OLLAMA_BASE_URL und die API-Hosts
//     eingebauter Cloud-Provider mit gesetztem Key.
// Alles andere: 403 und ein Logeintrag "[captain-egress] blockiert: …".
// Weiter geht es direkt oder ueber den Proxy aus CAPTAIN_HTTP(S)_PROXY /
// CAPTAIN_NO_PROXY (im Container HTTP_PROXY/HTTPS_PROXY/NO_PROXY).
//
// Geprueft wird auf Ebene Host:Port (bei HTTPS sieht ein Proxy nur CONNECT
// host:port). Pfad-Praefixe der Allowlist setzen die webfetch-Regeln und
// -Policies in opencode durch – fuer die erste URL, nicht fuer Weiterleitungen
// innerhalb desselben Hosts.
import http from "node:http";
import net from "node:net";

const DEFAULT_PORT = { "http:": 80, "https:": 443, "ws:": 80, "wss:": 443 };

const bare = (host) => host.toLowerCase().replace(/^\[(.*)\]$/, "$1");
export const key = (host, port) => `${bare(host)}:${Number(port)}`;

// URL -> "host:port" (oder null)
export function target(url) {
  let u;
  try { u = new URL(url); } catch { return null; }
  const port = u.port || DEFAULT_PORT[u.protocol];
  return u.hostname && port ? key(u.hostname, port) : null;
}

// API-Hosts der eingebauten Cloud-Provider (start.mjs: CLOUD_KEYS)
export const CLOUD_HOSTS = { ANTHROPIC_API_KEY: "api.anthropic.com", OPENAI_API_KEY: "api.openai.com", OPENROUTER_API_KEY: "openrouter.ai" };
const CLOUD_PROVIDERS = { ANTHROPIC_API_KEY: "anthropic", OPENAI_API_KEY: "openai", OPENROUTER_API_KEY: "openrouter" };

// Erlaubte Ziele: Allowlist-Praefixe + Infrastruktur aus bereinigter Config/Umgebung
export function allowedTargets(prefixes, clean, env, warn = () => {}) {
  const set = new Set();
  for (const p of prefixes) {
    const u = new URL(p);
    set.add(target(p));
    if (!u.port) for (const port of [80, 443]) set.add(key(u.hostname, port));
  }
  const cloud = new Set(Object.entries(CLOUD_PROVIDERS).filter(([k]) => env[k]).map(([, id]) => id));
  for (const [name, prov] of Object.entries(clean?.providers ?? {})) {
    const t = prov?.settings?.baseURL ? target(prov.settings.baseURL) : null;
    if (t) set.add(t);
    else if (!cloud.has(name)) warn(`providers.${name}: ohne gueltige settings.baseURL kennt der Egress-Filter das Ziel nicht – Anfragen dorthin werden blockiert (baseURL eintragen)`);
  }
  for (const s of Object.values(clean?.mcp?.servers ?? {})) {
    const t = target(s.url);
    if (t) set.add(t);
  }
  for (const k of ["LLM_BASE_URL", "OLLAMA_BASE_URL"]) {
    const t = env[k] ? target(env[k]) : null;
    if (t) set.add(t);
  }
  for (const [k, host] of Object.entries(CLOUD_HOSTS)) if (env[k]) set.add(key(host, 443));
  return set;
}

// NO_PROXY-Auswertung wie ueblich: "*", "host", ".domain", "host:port"
export function bypass(host, port, noProxy) {
  return String(noProxy ?? "").split(/[\s,]+/).some((entry) => {
    if (!entry) return false;
    if (entry === "*") return true;
    const m = entry.match(/^(.+?):(\d+)$/);
    if (m && Number(m[2]) !== Number(port)) return false;
    const h = bare((m ? m[1] : entry).replace(/^\*/, ""));
    if (h.startsWith(".")) return host === h.slice(1) || host.endsWith(h);
    return host === h || host.endsWith(`.${h}`);
  });
}

// Weiterer Proxy (Firmen-Proxy) aus der Umgebung von start.mjs; nur http://
export function upstream(kind, env) {
  const raw = kind === "connect"
    ? env.HTTPS_PROXY || env.https_proxy || env.HTTP_PROXY || env.http_proxy
    : env.HTTP_PROXY || env.http_proxy;
  if (!raw) return null;
  const u = new URL(raw);
  if (u.protocol !== "http:") throw new Error(`Proxy ${u.protocol}//${u.host}: nur http:// wird unterstuetzt`);
  return u;
}

function proxyAuth(up, headers) {
  if (!up.username) return headers;
  const cred = `${decodeURIComponent(up.username)}:${decodeURIComponent(up.password)}`;
  return { ...headers, "proxy-authorization": `Basic ${Buffer.from(cred).toString("base64")}` };
}

export function createGuard({ allowed, env = {}, log = () => {} }) {
  const noProxy = env.NO_PROXY ?? env.no_proxy;
  const via = (kind, host, port) => (bypass(host, port, noProxy) ? null : upstream(kind, env));
  // Fehlkonfiguration (z. B. https://-Proxy) sofort melden, nicht erst beim ersten Abruf
  upstream("connect", env);
  upstream("http", env);

  const server = http.createServer((req, res) => {
    let u = null;
    try { u = new URL(req.url); } catch { /* keine Proxy-Anfrage */ }
    if (!u || u.protocol !== "http:") {
      res.writeHead(400, { "content-type": "text/plain" }).end("captain-egress: nur Proxy-Anfragen\n");
      return;
    }
    const host = bare(u.hostname);
    const port = Number(u.port || 80);
    if (!allowed.has(key(host, port))) {
      log(`blockiert: ${host}:${port} (${req.method} http)`);
      res.writeHead(403, { "content-type": "text/plain" }).end("captain-egress: Ziel nicht erlaubt\n");
      return;
    }
    const headers = { ...req.headers };
    delete headers["proxy-connection"];
    delete headers["proxy-authorization"];
    const up = via("http", host, port);
    const opts = up
      ? { host: up.hostname, port: Number(up.port || 80), path: req.url, headers: proxyAuth(up, headers) }
      : { host, port, path: `${u.pathname}${u.search}`, headers };
    const out = http.request({ ...opts, method: req.method }, (r) => {
      res.writeHead(r.statusCode, r.headers);
      r.pipe(res);
    });
    out.on("error", (e) => {
      log(`Fehler ${host}:${port}: ${e.message}`);
      if (!res.headersSent) res.writeHead(502, { "content-type": "text/plain" }).end("captain-egress: Ziel nicht erreichbar\n");
      else res.destroy();
    });
    req.pipe(out);
  });

  server.on("connect", (req, sock, head) => {
    const i = req.url.lastIndexOf(":");
    const host = i > 0 ? bare(req.url.slice(0, i)) : "";
    const port = i > 0 ? Number(req.url.slice(i + 1)) : 0;
    if (!host || !Number.isInteger(port) || port < 1 || port > 65535 || !allowed.has(key(host, port))) {
      log(`blockiert: ${req.url} (CONNECT)`);
      sock.end("HTTP/1.1 403 Forbidden\r\ncontent-length: 0\r\n\r\n");
      return;
    }
    sock.on("error", () => {});
    const fail = (e) => {
      log(`Fehler ${host}:${port}: ${e.message}`);
      sock.end("HTTP/1.1 502 Bad Gateway\r\ncontent-length: 0\r\n\r\n");
    };
    const tunnel = (remote, remoteHead) => {
      remote.on("error", () => sock.destroy());
      sock.on("error", () => remote.destroy());
      sock.write("HTTP/1.1 200 Connection Established\r\n\r\n");
      if (head?.length) remote.write(head);
      if (remoteHead?.length) sock.write(remoteHead);
      remote.pipe(sock);
      sock.pipe(remote);
    };
    const up = via("connect", host, port);
    if (!up) {
      const remote = net.connect(port, host);
      remote.once("error", fail);
      remote.once("connect", () => { remote.removeListener("error", fail); tunnel(remote); });
      return;
    }
    const authority = host.includes(":") ? `[${host}]:${port}` : `${host}:${port}`;
    const r = http.request({ host: up.hostname, port: Number(up.port || 80), method: "CONNECT", path: authority,
      headers: proxyAuth(up, { host: authority }) });
    r.once("error", fail);
    r.on("connect", (res, remote, remoteHead) => {
      r.removeListener("error", fail);
      if (res.statusCode !== 200) {
        remote.destroy();
        log(`Proxy lehnt ${authority} ab: HTTP ${res.statusCode}`);
        sock.end(`HTTP/1.1 ${res.statusCode} Upstream\r\ncontent-length: 0\r\n\r\n`);
        return;
      }
      tunnel(remote, remoteHead);
    });
    r.end();
  });
  server.on("clientError", (_e, sock) => sock.destroy());
  return server;
}

// Startet den Filter auf 127.0.0.1 (freier Port) -> { server, url }
export function startGuard(opts) {
  const server = createGuard(opts);
  return new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve({ server, url: `http://127.0.0.1:${server.address().port}` }));
  });
}
