// Unit-Tests fuer start.mjs: node --test infra/opencode/start.test.mjs
// (laeuft auch ueber JUnit: StartScriptTest bzw. ./gradlew nodeTest)
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

import { buildConfig, enabledProviders, environment, FIXED, NO_PROVIDER, PACKAGES, parseJsonc, PROMPT_MAX, promptReader, refAllowed, resolveEnv, sanitize } from "./start.mjs";

const TEMPLATE = fs.readFileSync(new URL("./config/opencode.jsonc", import.meta.url), "utf8");
const TEMPLATE_AGENTS = new URL("./config/agents", import.meta.url).pathname;
const ENV = { LLM_BASE_URL: "http://llm/v1", LLM_API_KEY: "k", LLM_MODEL: "m1", LLM_CONTEXT: "131072", LLM_OUTPUT: "16384", OPENCODE_MODEL: "llm/m1",
  MM_BOT_TOKEN: "BOT-SECRET", OPENCODE_SERVER_PASSWORD: "PW-SECRET", MCP_X_TOKEN: "mcp-t" };

function build(text, env = ENV, agentsDir = TEMPLATE_AGENTS) {
  const warnings = [];
  const warn = (w) => warnings.push(w);
  const r = buildConfig(text, env, warn, promptReader(agentsDir, warn));
  return { ...r, warnings };
}

test("parseJsonc: Kommentare, Kommas, Strings mit // und Kommas", () => {
  const cfg = parseJsonc('// k\n{ "a": "http://x/*y*/", /* b */ "b": [1, 2,], "c": "\\"//\\"", }');
  assert.deepEqual(cfg, { a: "http://x/*y*/", b: [1, 2], c: '"//"' });
  assert.throws(() => parseJsonc("{ kaputt"));
});

test("Vorlage ergibt gueltige, platzhalterfreie Config", () => {
  const { clean, json, warnings } = build(TEMPLATE);
  assert.deepEqual(warnings, []);
  assert.equal(clean.model, "llm/m1");
  assert.equal(clean.providers.llm.settings.baseURL, "http://llm/v1");
  assert.deepEqual(Object.keys(clean.providers.llm.models), ["m1"]);
  assert.deepEqual(clean.providers.llm.models.m1.limit, { context: 131072, output: 16384 }); // Zahlen, kein Text
  assert.ok(!/\{(env|file):/.test(json));
  // Prompt des Beispiel-Agenten aus agents/reviewer.md
  assert.match(clean.agents.reviewer.system, /^# Rolle\n/);
  assert.equal(clean.agents.reviewer.mode, "primary");
  // ungueltige bzw. fehlende Limits: verworfen (Warnung), Rest bleibt
  const bad = build(TEMPLATE, { ...ENV, LLM_CONTEXT: "viel", LLM_OUTPUT: "" });
  assert.deepEqual(bad.clean.providers.llm.models.m1.limit, {});
  assert.equal(bad.warnings.length, 2);
});

test("Injektion ueber .env-Wert mit Anfuehrungszeichen bleibt ein String", () => {
  const evil = 'x", "plugins": ["./evil"], "mcp": {"servers": {"l": {"type": "local", "command": ["sh"]}}}, "y": "';
  const { clean, json } = build(TEMPLATE, { ...ENV, OPENCODE_MODEL: evil, LLM_MODEL: evil, LLM_API_KEY: 'a"b\\c' });
  assert.equal(clean.plugins, undefined);
  assert.deepEqual(Object.keys(clean.mcp.servers), []);
  assert.equal(clean.model, undefined); // kein gueltiges provider/modell
  assert.deepEqual(Object.keys(clean.providers.llm.models), [evil]); // nur als Schluessel-Text
  assert.equal(clean.providers.llm.settings.apiKey, 'a"b\\c');
  assert.deepEqual(JSON.parse(json), clean); // JSON bleibt gueltig
});

test("verbotene Variablen werden nie eingesetzt", () => {
  for (const name of ["MM_BOT_TOKEN", "OPENCODE_SERVER_PASSWORD", "CAPTAIN_SYSTEM_PROMPT", "HOME", "PATH", "MM_API_KEY",
    "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "OLLAMA_BASE_URL"]) {
    assert.equal(refAllowed(name), false, name);
  }
  for (const name of ["LLM_MODEL", "LLM_API_KEY", "MCP_X_TOKEN", "OPENCODE_MODEL"]) {
    assert.equal(refAllowed(name), true, name);
  }
  const text = `{ "mcp": { "servers": {
      "a": { "type": "remote", "url": "https://x/{env:MM_BOT_TOKEN}" },
      "b": { "type": "remote", "url": "https://x", "headers": { "Authorization": "Bearer {env:OPENCODE_SERVER_PASSWORD}" } },
      "c": { "type": "remote", "url": "https://x", "headers": { "Authorization": "Bearer {env:MCP_X_TOKEN}" } } } } }`;
  const { clean, json, warnings } = build(text);
  assert.ok(!json.includes("BOT-SECRET") && !json.includes("PW-SECRET"));
  assert.deepEqual(Object.keys(clean.mcp.servers), ["b", "c"]);
  assert.deepEqual(clean.mcp.servers.b.headers, {}); // Header verworfen
  assert.equal(clean.mcp.servers.c.headers.Authorization, "Bearer mcp-t");
  assert.ok(warnings.some((w) => w.includes("MM_BOT_TOKEN")));
});

test("{file:…} – direkt oder ueber einen .env-Wert – wird verworfen", () => {
  const text = '{ "model": "{file:/etc/passwd}", "providers": { "p": { "settings": { "apiKey": "{env:LLM_API_KEY}" } } } }';
  const { clean, json } = build(text, { ...ENV, LLM_API_KEY: "{file:/etc/shadow}" });
  assert.equal(clean.model, undefined);
  assert.deepEqual(clean.providers.p.settings, {});
  assert.ok(!/\{(env|file):/.test(json));
  // verschachtelt: Platzhalter, der erst nach dem Einsetzen entsteht
  const { json: j2 } = build('{ "model": "llm/{env:LLM_MODEL}" }', { LLM_MODEL: "{env:MM_BOT_TOKEN}" });
  assert.ok(!j2.includes("{env:"));
});

test("gesperrte Schluessel und lokale MCP-Server", () => {
  const cfg = {
    plugins: ["./x"], agents: { build: {} }, default_agent: "plan", commands: {}, skills: [], instructions: [],
    references: {}, shell: "/bin/sh", enterprise: {}, experimental: { policies: [] }, policies: [], worktree: {}, tool_output: {},
    mcp: { servers: {
      l: { type: "local", command: ["sh"] },
      e: { type: "{env:X}", url: "https://x" },
      r: { type: "remote", url: "https://x", codemode: true, oauth: { clientId: "x" }, command: ["sh"], environment: {} },
    } },
  };
  const warnings = [];
  const clean = sanitize(cfg, (w) => warnings.push(w));
  assert.deepEqual(Object.keys(clean), ["mcp"]);
  assert.deepEqual(clean.mcp.servers, { r: { type: "remote", url: "https://x", oauth: false, codemode: false } });
  assert.ok(warnings.some((w) => w.includes("agents.build")));
  for (const k of ["plugins", "default_agent", "commands", "skills", "instructions", "references", "shell", "enterprise", "experimental", "policies"]) {
    assert.ok(warnings.some((w) => w.includes(`"${k}"`)), k);
  }
});

test("agents: nur eigene Namen, Prompt/Modell/Schritte, immer primary, keine Rechte", () => {
  const warnings = [];
  const clean = sanitize({ agents: {
    reviewer: { system: "Du pruefst MRs.", description: "MR-Review", model: "llm/m", steps: 40, mode: "subagent",
      permissions: [{ action: "shell", resource: "*", effect: "allow" }], tools: { bash: true } },
    plan: { system: "x" },
    "../x": { system: "x" },
    leer: { description: "ohne Prompt" },
    kaputt: { system: "x", model: "ohne-slash", steps: 0 },
  } }, (w) => warnings.push(w));
  assert.deepEqual(clean.agents, {
    reviewer: { system: "Du pruefst MRs.", description: "MR-Review", model: "llm/m", steps: 40, mode: "primary" },
    kaputt: { system: "x", mode: "primary" },
  });
  for (const w of ["agents.reviewer.mode", "agents.reviewer.permissions", "agents.reviewer.tools", "agents.plan", "agents.../x",
    "agents.leer", "agents.kaputt.model", "agents.kaputt.steps"]) {
    assert.ok(warnings.some((x) => x.includes(w)), w);
  }
  assert.equal(sanitize({ agents: { plan: { system: "x" } } }).agents, undefined);
});

test("agents: Prompt aus agents/<name>.md", () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "captain-agents-"));
  fs.writeFileSync(path.join(dir, "datei.md"), "\uFEFF# Prompt\nZeile 2\n");
  fs.writeFileSync(path.join(dir, "beides.md"), "aus Datei");
  fs.writeFileSync(path.join(dir, "env.md"), "Token: {env:LLM_API_KEY}");
  fs.writeFileSync(path.join(dir, "gross.md"), "x".repeat(PROMPT_MAX + 1));
  fs.writeFileSync(path.join(dir, "leer.md"), "  \n");
  fs.symlinkSync("/etc/passwd", path.join(dir, "link.md"));
  fs.mkdirSync(path.join(dir, "ordner.md"));
  const warnings = [];
  const warn = (w) => warnings.push(w);
  const clean = sanitize({ agents: {
    datei: { steps: 5 }, beides: { system: "aus Config" }, env: {}, gross: {}, leer: {}, link: {}, ordner: {}, fehlt: {},
  } }, warn, promptReader(dir, warn));
  assert.deepEqual(clean.agents, {
    datei: { steps: 5, system: "# Prompt\nZeile 2\n", mode: "primary" },
    beides: { system: "aus Config", mode: "primary" },
  });
  for (const w of ["beides.md wird ignoriert", "agents.env: Prompt enthaelt", "gross.md: groesser", "agents.leer: kein Prompt",
    "link.md: keine normale Datei", "ordner.md: keine normale Datei", "agents.fehlt: kein Prompt"]) {
    assert.ok(warnings.some((x) => x.includes(w)), w);
  }
});

test("Provider: nur eingebaute Pakete, Unterbaeume per Allowlist", () => {
  const warnings = [];
  const clean = sanitize({ providers: {
    a: { package: "file:///etc/captain/x.js" },
    b: { package: "evil-npm-package" },
    c: { package: "@ai-sdk/openai-compatible", env: ["MM_BOT_TOKEN"], body: { x: 1 },
         settings: { baseURL: "u", fetch: "x", apiKey: "k" },
         models: { m: { name: "M", package: "file:///x", limit: { context: 1, evil: 2 }, variants: [] } } },
  } }, (w) => warnings.push(w));
  assert.deepEqual(Object.keys(clean.providers), ["c"]);
  assert.deepEqual(clean.providers.c, { package: "@ai-sdk/openai-compatible", settings: { baseURL: "u", apiKey: "k" },
    models: { m: { name: "M", limit: { context: 1 } } } });
  assert.ok(PACKAGES.has("@opencode/ai/providers/openai-compatible"));
  // keine Cloud-Provider-Pakete
  assert.ok(!PACKAGES.has("@opencode/ai/providers/anthropic") && !PACKAGES.has("@opencode/ai/providers/openrouter"));
});

test("permissions: nur allow/deny mit genau drei Feldern", () => {
  const clean = sanitize({ permissions: [
    { action: "x_y", resource: "*", effect: "allow" },
    { action: "x", resource: "*", effect: "ask" },
    { action: "x", resource: "*", effect: "allow", extra: 1 },
    "shell",
  ] });
  assert.deepEqual(clean.permissions, [{ action: "x_y", resource: "*", effect: "allow" }]);
});

test("__proto__ und constructor werden nicht uebernommen", () => {
  const { clean } = build('{ "__proto__": { "plugins": ["x"] }, "providers": { "__proto__": { "package": "x" }, "constructor": {} } }');
  assert.equal(Object.getPrototypeOf(clean), Object.prototype);
  assert.equal(clean.plugins, undefined);
  assert.equal({}.plugins, undefined);
  assert.deepEqual(Object.keys(clean.providers ?? {}), []);
});

test("Umgebung fuer opencode: Allowlist, feste Schalter", () => {
  const env = environment({ ...ENV, PATH: "/bin", ANTHROPIC_API_KEY: "a", MM_API_KEY: "x", LD_PRELOAD: "/x.so",
    NODE_OPTIONS: "--require x", XDG_CONFIG_HOME: "/tmp", OPENCODE_CONFIG_DIR: "/tmp", OPENCODE_CONFIG: "/x",
    OPENCODE_CONFIG_CONTENT: "{}", OPENCODE_DISABLE_PROJECT_CONFIG: "0" });
  // Keys (auch LLM_API_KEY) setzt das Startskript in die Config ein – opencode bekommt keine
  assert.deepEqual(Object.keys(env).sort(), ["OPENCODE_SERVER_PASSWORD", "PATH", ...Object.keys(FIXED)].sort());
  assert.equal(env.OPENCODE_CONFIG, "/run/captain/opencode.json");
  assert.equal(env.OPENCODE_DISABLE_PROJECT_CONFIG, "1");
  assert.equal(JSON.parse(env.OPENCODE_CONFIG_CONTENT).share, "disabled");
  assert.equal(env.OPENCODE_DISABLE_MODELS_FETCH, "1");
  // ohne Angabe: kein Provider freigegeben (leere Liste hiesse „alle“)
  assert.deepEqual(JSON.parse(env.OPENCODE_CONFIG_CONTENT).enabled_providers, [NO_PROVIDER]);
});

test("Provider: nur die der Admin-Config", () => {
  const clean = { providers: { llm: {}, zweit: {} } };
  assert.deepEqual(enabledProviders(clean), ["llm", "zweit"]);
  assert.deepEqual(enabledProviders({}), [NO_PROVIDER]);
  // eingebaute Cloud-Provider (auch Zen "opencode") nie
  assert.ok(!enabledProviders(clean).includes("opencode"));
  const env = environment({}, ["llm"]);
  assert.deepEqual(JSON.parse(env.OPENCODE_CONFIG_CONTENT).enabled_providers, ["llm"]);
  assert.equal(JSON.parse(env.OPENCODE_CONFIG_CONTENT).share, "disabled");
});

test("resolveEnv setzt Werte ein, ohne Struktur zu erzeugen", () => {
  const out = resolveEnv({ a: "{env:LLM_X}", "{env:LLM_K}": 1, list: ["{env:MM_BOT_TOKEN}", "ok"] },
    { LLM_X: '"}', LLM_K: "__proto__", MM_BOT_TOKEN: "s" });
  assert.deepEqual(out, { a: '"}', list: ["ok"] });
});
