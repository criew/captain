// Start von opencode fuer Captain: Admin-Config pruefen/bereinigen, Umgebung
// festzurren, dann `opencode serve` starten.
//
// Die Admin-Config (/etc/captain/opencode.jsonc, im Betrieb vom Host
// eingebunden) darf nur Provider, Modelle, Limits, Remote-MCP-Server und
// Freigaben setzen. opencode 2.0.20 fuehrt u. a. "plugins", "agents",
// "commands", "skills", "instructions", "references", "shell", "enterprise"
// und lokale MCP-Server (type "local" = beliebiger Prozess als root) aus
// JEDER Config-Quelle zusammen – die feste Basis kann sie dort nicht
// ueberschreiben. Deshalb liest opencode nicht die Admin-Datei selbst, sondern
// eine hier erzeugte, bereinigte Kopie:
//
//   1. JSONC parsen,
//   2. {env:NAME} selbst aufloesen – auf Werte-Ebene (JSON-sicher), nur fuer
//      erlaubte Namen (REF_OK); opencode ersetzt Platzhalter sonst TEXTUELL
//      und ohne Escaping, ein Wert mit '"' koennte neue Schluessel erzeugen,
//   3. verbleibende {env:…}/{file:…} verwerfen (die Kopie enthaelt keine
//      Platzhalter mehr, opencode ersetzt also nichts),
//   4. per Allowlist bereinigen (pro Block feste Schluessel),
//   5. als JSON schreiben (fail closed: ohne Kopie kein Start).
//
// Verworfenes steht als WARNUNG im Log. Aenderungen an der Admin-Datei wirken
// erst nach einem Neustart des Containers. opencode selbst bekommt nur eine
// kleine Umgebungs-Allowlist; die Sicherheitsschalter sind fest.
//
// webfetch: CAPTAIN_WEBFETCH_ALLOW (kommagetrennte URL-Praefixe, leer = aus)
// wird hier zu experimental.policies in OPENCODE_CONFIG_CONTENT (harte
// Obergrenze, siehe webfetchPolicies) und – wenn gesetzt – zu einem
// Egress-Filter (egress.mjs), weil opencode Weiterleitungen ungeprueft folgt.
import { spawn } from "node:child_process";
import fs from "node:fs";
import { pathToFileURL } from "node:url";

import { allowedTargets, startGuard } from "./egress.mjs";

export const SOURCE = "/etc/captain/opencode.jsonc";
export const TARGET = "/run/captain/opencode.json";
export const COMMAND = ["opencode", "serve", "--hostname", "0.0.0.0", "--port", "4096", "--print-logs", "--log-level", "info"];

// --- JSONC -> Objekt (Kommentare und Kommas vor } ] entfernen, Strings schonen)
export function parseJsonc(text) {
  let out = "";
  for (let i = 0; i < text.length; ) {
    const c = text[i];
    if (c === '"') {
      let j = i + 1;
      while (j < text.length && text[j] !== '"') j += text[j] === "\\" ? 2 : 1;
      out += text.slice(i, j + 1);
      i = j + 1;
    } else if (c === "/" && text[i + 1] === "/") {
      while (i < text.length && text[i] !== "\n") i++;
    } else if (c === "/" && text[i + 1] === "*") {
      const end = text.indexOf("*/", i + 2);
      i = end < 0 ? text.length : end + 2;
    } else {
      out += c;
      i++;
    }
  }
  let res = "";
  for (let k = 0; k < out.length; k++) {
    const c = out[k];
    if (c === '"') {
      let j = k + 1;
      while (j < out.length && out[j] !== '"') j += out[j] === "\\" ? 2 : 1;
      res += out.slice(k, j + 1);
      k = j;
      continue;
    }
    if (c === "," && /^\s*[}\]]/.test(out.slice(k + 1))) continue;
    res += c;
  }
  return JSON.parse(res.trim() === "" ? "{}" : res);
}

const isObj = (v) => v !== null && typeof v === "object" && !Array.isArray(v);
const BAD_KEYS = new Set(["__proto__", "constructor", "prototype"]);
const PLACEHOLDER = /\{(env|file):/;

// Referenzierbare Umgebungsvariablen (alles andere – u. a. MM_*,
// OPENCODE_SERVER_PASSWORD, CAPTAIN_* – wird nie eingesetzt).
export const REF_OK = /^(LLM_[A-Z0-9_]+|OLLAMA_[A-Z0-9_]+|MCP_[A-Z0-9_]+|[A-Z0-9_]+_API_KEY|OPENCODE_MODEL|OPENCODE_VARIANT)$/;
export const REF_NEVER = /^(MM_|CAPTAIN_|OPENCODE_SERVER_PASSWORD$)/;
export const refAllowed = (name) => REF_OK.test(name) && !REF_NEVER.test(name);

class Drop extends Error {}

function resolveString(s, env, path, warn) {
  const out = s.replace(/\{env:([^}]*)\}/g, (_m, name) => {
    if (!refAllowed(name)) {
      warn(`${path}: {env:${name}} nicht erlaubt (nur LLM_*, OLLAMA_*, MCP_*, *_API_KEY, OPENCODE_MODEL/VARIANT)`);
      throw new Drop();
    }
    return env[name] ?? "";
  });
  if (PLACEHOLDER.test(out)) {
    warn(`${path}: {env:…}/{file:…} nach dem Einsetzen nicht erlaubt`);
    throw new Drop();
  }
  return out;
}

// {env:…} auf Werte-Ebene einsetzen (auch in Schluesseln); Unzulaessiges faellt weg.
export function resolveEnv(value, env, warn = () => {}, path = "$") {
  if (typeof value === "string") return resolveString(value, env, path, warn);
  if (Array.isArray(value)) {
    const out = [];
    value.forEach((v, i) => {
      try { out.push(resolveEnv(v, env, warn, `${path}[${i}]`)); } catch (e) { if (!(e instanceof Drop)) throw e; }
    });
    return out;
  }
  if (isObj(value)) {
    const out = {};
    for (const [k, v] of Object.entries(value)) {
      try {
        const key = resolveString(k, env, `${path}.${k}`, warn);
        if (BAD_KEYS.has(key)) { warn(`${path}.${key}: Schluessel nicht erlaubt`); continue; }
        out[key] = resolveEnv(v, env, warn, `${path}.${key}`);
      } catch (e) { if (!(e instanceof Drop)) throw e; }
    }
    return out;
  }
  return value;
}

// Nur diese Provider-Pakete (in opencode 2.0.20 eingebaut). Alles andere
// wuerde opencode per import (file://) oder npm-Installation zur Laufzeit laden.
export const PACKAGES = new Set([
  "@ai-sdk/openai-compatible",
  ...["amazon-bedrock", "anthropic", "anthropic-compatible", "azure", "baseten", "cerebras",
    "cloudflare-ai-gateway", "cloudflare-workers-ai", "deepinfra", "deepseek", "fireworks", "google",
    "google-vertex", "groq", "mistral", "openai", "openai-compatible", "openrouter", "togetherai", "xai",
  ].map((p) => `@opencode/ai/providers/${p}`),
]);

const str = (v) => typeof v === "string";
const num = (v) => typeof v === "number" && Number.isFinite(v) && v >= 0;
const bool = (v) => typeof v === "boolean";
const strMap = (v) => isObj(v) && Object.values(v).every(str);

// Allowlist je Block: Schluessel -> Pruefung (Funktion) oder Unter-Schema (Objekt)
const LIMIT = { context: num, input: num, output: num };
const MODEL = { modelID: str, name: str, disabled: bool, limit: LIMIT };
const SETTINGS = { baseURL: str, apiKey: str, timeout: num, chunkTimeout: num, includeUsage: bool };
const PROVIDER = { name: str, package: (v) => PACKAGES.has(v), settings: SETTINGS, headers: strMap };
const REMOTE = { type: (v) => v === "remote", url: str, headers: strMap, disabled: bool, timeout: num };
const COMPACTION = { auto: bool, buffer: num, keep: { tokens: num } };
const RULE = { action: str, resource: str, effect: (v) => v === "allow" || v === "deny" };

function pick(obj, schema, path, warn) {
  const out = {};
  for (const [k, v] of Object.entries(obj)) {
    const check = Object.hasOwn(schema, k) ? schema[k] : undefined;
    if (check === undefined) { warn(`${path}.${k} nicht erlaubt – verworfen`); continue; }
    if (typeof check === "function") {
      if (check(v)) out[k] = v;
      else warn(`${path}.${k}: ungueltiger Wert ${JSON.stringify(v)} – verworfen`);
    } else if (isObj(v)) out[k] = pick(v, check, `${path}.${k}`, warn);
    else warn(`${path}.${k}: Objekt erwartet – verworfen`);
  }
  return out;
}

export function sanitize(cfg, warn = () => {}) {
  const out = {};
  for (const [key, value] of Object.entries(isObj(cfg) ? cfg : {})) {
    if (key === "$schema") continue;
    if (key === "model") {
      if (str(value) && /^[A-Za-z0-9._-]+\/[A-Za-z0-9._:@+\/-]+$/.test(value)) out.model = value;
      else warn(`model: ungueltig ${JSON.stringify(value)} – verworfen`);
    } else if (key === "providers" && isObj(value)) {
      out.providers = {};
      for (const [name, p] of Object.entries(value)) {
        if (!isObj(p)) continue;
        if (p.package !== undefined && !PACKAGES.has(p.package)) {
          warn(`providers.${name}.package "${p.package}" nicht erlaubt (nur eingebaute Pakete) – Provider verworfen`);
          continue;
        }
        const { models, ...rest } = p;
        const clean = pick(rest, PROVIDER, `providers.${name}`, warn);
        if (models !== undefined) {
          clean.models = {};
          for (const [mid, m] of Object.entries(isObj(models) ? models : {})) {
            if (isObj(m)) clean.models[mid] = pick(m, MODEL, `providers.${name}.models.${mid}`, warn);
          }
        }
        out.providers[name] = clean;
      }
    } else if (key === "mcp" && isObj(value)) {
      const servers = {};
      for (const [k] of Object.entries(value)) {
        if (k !== "servers" && k !== "timeout") warn(`mcp.${k} nicht erlaubt – verworfen`);
      }
      for (const [name, s] of Object.entries(isObj(value.servers) ? value.servers : {})) {
        if (!isObj(s) || s.type !== "remote" || !str(s.url)) {
          warn(`mcp.servers.${name}: nur "type": "remote" mit "url" erlaubt (keine lokalen Prozesse) – verworfen`);
          continue;
        }
        const { codemode: _c, oauth: _o, ...rest } = s;
        // Code Mode ist gesperrt, OAuth headless nicht moeglich
        servers[name] = { ...pick(rest, REMOTE, `mcp.servers.${name}`, warn), oauth: false, codemode: false };
      }
      out.mcp = { servers, ...(num(value.timeout) ? { timeout: value.timeout } : {}) };
    } else if (key === "permissions" && Array.isArray(value)) {
      out.permissions = value.filter((r) => {
        const ok = isObj(r) && Object.keys(r).length === 3 && Object.entries(RULE).every(([k, f]) => f(r[k]));
        if (!ok) warn(`permissions: nur {action, resource, effect: allow|deny} erlaubt ("ask" wuerde headless haengen) – verworfen: ${JSON.stringify(r)}`);
        return ok;
      });
    } else if (key === "compaction" && isObj(value)) {
      out.compaction = pick(value, COMPACTION, "compaction", warn);
    } else {
      warn(`"${key}" ist in der Admin-Config nicht erlaubt (hebelt die Sicherheit aus) – verworfen`);
    }
  }
  return out;
}

// Admin-Datei (Text) -> bereinigte Config; wirft bei ungueltigem JSONC.
export function buildConfig(text, env, warn = () => {}) {
  const clean = sanitize(resolveEnv(parseJsonc(text), env, warn), warn);
  const json = JSON.stringify(clean, null, 2);
  if (PLACEHOLDER.test(json)) throw new Error("Platzhalter in der bereinigten Config");
  return { clean, json };
}

// --- Erlaubte Provider ----------------------------------------------------------
// opencode bringt eingebaute Cloud-Provider mit (u. a. "opencode" mit
// kostenlosen Zen-Modellen). Ohne Einschraenkung koennte jeder Chat per
// !modell dorthin wechseln – Chat-Inhalte verliessen dann das Haus. Erlaubt
// sind deshalb nur die Provider der Admin-Config plus eingebaute
// Cloud-Provider, deren API-Key ausdruecklich gesetzt ist.
export const CLOUD_KEYS = { ANTHROPIC_API_KEY: "anthropic", OPENAI_API_KEY: "openai", OPENROUTER_API_KEY: "openrouter" };
// Platzhalter, falls gar nichts konfiguriert ist: leere Liste hiesse bei
// opencode "keine Einschraenkung".
export const NO_PROVIDER = "captain-kein-provider";

export function enabledProviders(clean, env) {
  const set = new Set(Object.keys(clean?.providers ?? {}));
  for (const [key, id] of Object.entries(CLOUD_KEYS)) if (env[key]) set.add(id);
  return set.size ? [...set].sort() : [NO_PROVIDER];
}

// --- webfetch-Allowlist (CAPTAIN_WEBFETCH_ALLOW) ------------------------------
// Gleiche Normalisierung wie captain/webfetch.py (gemeinsame Testfaelle:
// tests/data/webfetch_allow.json). opencode 2.0.20 prueft bei webfetch die
// ROHE URL des Modells gegen Muster: "*" = beliebig viel (auch "/"),
// "?" = genau ein beliebiges Zeichen, "\\" im Wert zaehlt als "/", Gross-/
// Kleinschreibung zaehlt. Ein Eintrag wird deshalb zu "<praefix>" und
// "<praefix>/*" – das "/" verhindert Praefix-Tricks wie
// http://text-example.org.evil.com oder http://text-example.org@evil.com.
export const WEBFETCH_ENV = "CAPTAIN_WEBFETCH_ALLOW";
const WF_PORT = { http: 80, https: 443 };
const WF_ENTRY = /^([A-Za-z]+):\/\/(\[[0-9A-Fa-f:.]+\]|[^/:[\]]+)(?::([0-9]{1,5}))?(\/.*)?$/s;
const WF_LABEL = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const WF_FORBIDDEN = /[*?#\\@\s\x00-\x1f\x7f]/;
// Nach den Freigaben verboten: ".."-Segmente (auch kodiert) und Zeichen, die
// der URL-Parser still entfernt (Tab/LF/CR). "?" ist hier der Platzhalter.
export const WEBFETCH_DENY = ["*/..", "*/..?*", "*/%2e*", "*/%2E*", "*/.%2e*", "*/.%2E*", "*\t*", "*\n*", "*\r*"];

export function normalizeWebfetchEntry(entry) {
  if (!/^[\x00-\x7f]*$/.test(entry)) throw new Error("nur ASCII (IDN als Punycode xn--…)");
  const bad = entry.match(WF_FORBIDDEN);
  if (bad) throw new Error(`Zeichen ${JSON.stringify(bad[0])} nicht erlaubt (keine Platzhalter, Query, Userinfo)`);
  const m = entry.match(WF_ENTRY);
  if (!m) throw new Error("Format: http(s)://host[:port][/pfad]");
  const scheme = m[1].toLowerCase();
  if (!Object.hasOwn(WF_PORT, scheme)) throw new Error("nur http:// oder https://");
  const host = m[2].toLowerCase();
  if (!host.startsWith("[") && (host.length > 253 || !host.split(".").every((l) => WF_LABEL.test(l)))) {
    throw new Error("ungueltiger Hostname (nur a-z, 0-9, '-', Punkte; IDN als Punycode)");
  }
  let port = "";
  if (m[3] !== undefined) {
    const n = Number(m[3]);
    if (!(n > 0 && n < 65536)) throw new Error("ungueltiger Port");
    if (n !== WF_PORT[scheme]) port = `:${n}`;
  }
  const path = (m[4] ?? "").replace(/\/+$/, "");
  const segments = path.split("/").slice(1);
  if (segments.some((s) => s === "." || s === ".." || s.toLowerCase().includes("%2e")) || path.includes("//")) {
    throw new Error("Pfad ohne '.', '..', '%2e' und leere Segmente");
  }
  return `${scheme}://${host}${port}${path}`;
}

// "a, b" -> kanonische Praefixe; wirft bei einem ungueltigen Eintrag (fail closed)
export function parseWebfetchAllow(value) {
  const out = [];
  for (const raw of String(value ?? "").split(",")) {
    const entry = raw.trim();
    if (!entry) continue;
    let prefix;
    try {
      prefix = normalizeWebfetchEntry(entry);
    } catch (e) {
      throw new Error(`${WEBFETCH_ENV}: Eintrag ${JSON.stringify(entry)} ungueltig: ${e.message}`);
    }
    if (!out.includes(prefix)) out.push(prefix);
  }
  return out;
}

export const webfetchPatterns = (prefixes) => prefixes.flatMap((p) => [p, `${p}/*`]);

// Policies (Format "<aktion>:<ressource>"): bei mehreren Treffern gewinnt die
// LETZTE der fruehesten Quelle. Die Sicherheitsbasis enthaelt deshalb KEINE
// webfetch-Policy (sie wuerde jede Freigabe hier schlagen); diese hier stehen
// in OPENCODE_CONFIG_CONTENT, und die Admin-Config darf "experimental" nicht
// setzen. Ohne Allowlist bleibt nur "webfetch:*" deny – wie bisher.
export function webfetchPolicies(prefixes) {
  const rule = (resource, effect) => ({ action: "permission", resource: `webfetch:${resource}`, effect });
  if (!prefixes.length) return [rule("*", "deny")];
  return [rule("*", "deny"), ...webfetchPatterns(prefixes).map((p) => rule(p, "allow")), ...WEBFETCH_DENY.map((p) => rule(p, "deny"))];
}

// --- Umgebung fuer opencode ---------------------------------------------------
const FIXED_CONFIG = { share: "disabled", update: "disable", websearch: false, lsp: false, formatter: false, snapshots: false };
export const FIXED = {
  HOME: "/root",
  OPENCODE_DISABLE_PROJECT_CONFIG: "1",
  OPENCODE_CONFIG_PROJECT_DISABLE: "1",
  OPENCODE_CONFIG: TARGET,
  OPENCODE_CONFIG_CONTENT: JSON.stringify(FIXED_CONFIG),
  OPENCODE_DISABLE_AUTOUPDATE: "1",
  // Keine Modell-Liste von models.dev nachladen (externer Abruf)
  OPENCODE_DISABLE_MODELS_FETCH: "1",
};
const PASS = new Set(["PATH", "TZ", "LANG", "LC_ALL", "OPENCODE_SERVER_PASSWORD", "NODE_EXTRA_CA_CERTS", "SSL_CERT_FILE",
  "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy"]);
// Eingebaute Cloud-Provider lesen ihre Keys selbst aus der Umgebung
const PASS_RE = /^[A-Z0-9_]+_API_KEY$/;

// Mit Egress-Filter (proxy) laeuft aller Verkehr von opencode ueber ihn; nur
// Loopback geht direkt. Der Filter selbst nutzt die urspruenglichen
// HTTP(S)_PROXY/NO_PROXY.
const LOOPBACK = "localhost,127.0.0.1,::1";

export function environment(env, enabled = [NO_PROVIDER], { webfetch = [], proxy = null } = {}) {
  const out = {};
  for (const [k, v] of Object.entries(env)) {
    if (PASS.has(k) || (PASS_RE.test(k) && !REF_NEVER.test(k))) out[k] = v;
  }
  const proxied = proxy
    ? { HTTP_PROXY: proxy, HTTPS_PROXY: proxy, http_proxy: proxy, https_proxy: proxy, NO_PROXY: LOOPBACK, no_proxy: LOOPBACK }
    : {};
  return {
    ...out,
    ...proxied,
    ...FIXED,
    OPENCODE_CONFIG_CONTENT: JSON.stringify({
      ...FIXED_CONFIG,
      enabled_providers: enabled,
      experimental: { policies: webfetchPolicies(webfetch) },
    }),
  };
}

async function main() {
  const warn = (msg) => console.error(`[captain-start] WARNUNG: ${msg}`);
  const fail = (msg) => {
    console.error(`[captain-start] FEHLER: ${msg} – opencode startet nicht.`);
    process.exit(1);
  };
  let text = "{}";
  try {
    text = fs.readFileSync(SOURCE, "utf8");
  } catch (e) {
    if (e.code !== "ENOENT") fail(`${SOURCE} nicht lesbar: ${e.message}`);
    warn(`${SOURCE} fehlt – opencode startet ohne Admin-Config`);
  }
  let built;
  try {
    built = buildConfig(text, process.env, warn);
  } catch (e) {
    fail(`${SOURCE} ist kein gueltiges JSONC: ${e.message}`);
  }
  try {
    fs.mkdirSync("/run/captain", { recursive: true, mode: 0o700 });
    fs.writeFileSync(TARGET, built.json + "\n", { mode: 0o600 });
  } catch (e) {
    fail(`${TARGET} nicht schreibbar: ${e.message}`);
  }
  const c = built.clean;
  const enabled = enabledProviders(c, process.env);
  console.error(`[captain-start] Admin-Config geprueft: ${Object.keys(c).join(", ") || "(leer)"}; MCP-Server: ${Object.keys(c.mcp?.servers ?? {}).join(", ") || "keine"}; Provider: ${enabled.join(", ")}`);

  let webfetch = [];
  try {
    webfetch = parseWebfetchAllow(process.env[WEBFETCH_ENV]);
  } catch (e) {
    fail(e.message);
  }
  let proxy = null;
  if (webfetch.length) {
    const allowed = allowedTargets(webfetch, c, process.env, warn);
    try {
      ({ url: proxy } = await startGuard({ allowed, env: process.env, log: (msg) => console.error(`[captain-egress] ${msg}`) }));
    } catch (e) {
      fail(`Egress-Filter nicht startbar: ${e.message}`);
    }
    console.error(`[captain-start] webfetch erlaubt fuer: ${webfetch.join(", ")}; Egress-Filter ${proxy}, Ziele: ${[...allowed].join(", ")}`);
  } else {
    console.error("[captain-start] webfetch aus (CAPTAIN_WEBFETCH_ALLOW leer)");
  }

  const child = spawn(COMMAND[0], COMMAND.slice(1), { stdio: "inherit", env: environment(process.env, enabled, { webfetch, proxy }) });
  for (const sig of ["SIGTERM", "SIGINT", "SIGHUP"]) process.on(sig, () => child.kill(sig));
  child.on("error", (e) => fail(`opencode nicht startbar: ${e.message}`));
  child.on("exit", (code, signal) => process.exit(code ?? (signal ? 128 : 1)));
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main();
