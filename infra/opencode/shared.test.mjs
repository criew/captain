// Unit-Tests fuer das geteilte Verzeichnis (shared.mjs, start.mjs):
// node --test infra/opencode/shared.test.mjs (laeuft auch ueber pytest:
// tests/test_start_script.py). Verhalten gegen echtes opencode:
// tests/test_shared_integration.py.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

import { checkHomeOverlap, checkMount, GIT_PATTERNS, normalizeSharedDir, scan, SHARED_MOUNT, sharedPolicies } from "./shared.mjs";
import { environment, parseJsonc } from "./start.mjs";

const CASES = JSON.parse(fs.readFileSync(new URL("../../tests/data/shared_dir.json", import.meta.url), "utf8"));
const BASE = parseJsonc(fs.readFileSync(new URL("./config/base.jsonc", import.meta.url), "utf8"));

// Nachbau des Matchers von opencode 2.0.20 (wie webfetch.test.mjs)
function wildcard(value, pattern) {
  const v = value.replaceAll("\\", "/");
  let rx = pattern.replaceAll("\\", "/").replace(/[.+^${}()|[\]\\]/g, "\\$&").replace(/\*/g, ".*").replace(/\?/g, ".");
  if (rx.endsWith(" .*")) rx = rx.slice(0, -3) + "( .*)?";
  return new RegExp("^" + rx + "$", "s").test(v);
}
// Policies: frueheste Quelle gewinnt, darin die letzte passende
function blocked(sources, action, resource) {
  const list = [...sources].reverse().flatMap((s) => s.experimental?.policies ?? []);
  const hit = list.findLast((p) => p.action === "permission" && wildcard(`${action}:${resource}`, p.resource));
  return hit?.effect === "deny";
}
const content = (env) => JSON.parse(env.OPENCODE_CONFIG_CONTENT);

test("Pfadpruefung: gemeinsame Testfaelle (wie captain/shared.py)", () => {
  for (const c of CASES.valid) assert.equal(normalizeSharedDir(c.input), c.path, c.input);
  for (const v of CASES.invalid) assert.throws(() => normalizeSharedDir(v), /CAPTAIN_SHARED_DIR/, JSON.stringify(v));
  assert.equal(normalizeSharedDir(undefined), null);
});

test("keine Ueberschneidung mit CAPTAIN_HOME", () => {
  for (const s of ["/opt/captain", "/opt/captain/shared", "/opt"]) {
    assert.throws(() => checkHomeOverlap(s, "/opt/captain/"), /CAPTAIN_HOME/, s);
  }
  assert.throws(() => checkHomeOverlap("/srv/data", "/srv/data/captain"), /CAPTAIN_HOME/);
  for (const s of ["/opt/captain-shared", "/srv/captain-shared", null]) checkHomeOverlap(s, "/opt/captain");
  checkHomeOverlap("/srv/x", undefined);
});

test("Policies: external_directory nur fuer /shared/*, .git und edit gesperrt", () => {
  const on = [BASE, {}, content(environment({}, ["llm"], { shared: true }))];
  for (const r of ["/shared/*", "/shared/sub/*", "/shared/Team Infos/*", "/shared/.github/*", "/shared/a.git/*"]) {
    assert.equal(blocked(on, "external_directory", r), false, r);
  }
  for (const r of ["*", "/*", "/sharedX/*", "/shared-x/*", "/SHARED/*", "/tmp/captain/*", "/tmp/captain/ses_x/*",
    "/etc/*", "/root/.local/share/opencode/*", "/shared/.git/*", "/shared/repo/.git/*", "/shared/a/b/.git/objects/*"]) {
    assert.ok(blocked(on, "external_directory", r), r);
  }
  for (const r of ["/shared/.git", "/shared/repo/.git", "/shared/repo/.git/config"]) assert.ok(blocked(on, "read", r), r);
  assert.equal(blocked(on, "read", "/shared/repo/README.md"), false);
  for (const r of ["/shared", "/shared/a.txt", "/shared/sub/b.md"]) assert.ok(blocked(on, "edit", r), r);
  assert.equal(blocked(on, "edit", "notiz.txt"), false);
  // alles andere bleibt, wie es war
  assert.ok(blocked(on, "shell", "ls") && blocked(on, "webfetch", "http://x/") && blocked(on, "websearch", "x"));
  assert.ok(blocked(on, "subagent", "x") && blocked(on, "skill", "x") && blocked(on, "opencode_read_mcp_resource", "x"));
  assert.deepEqual(GIT_PATTERNS, ["/shared/.git", "/shared/.git/*", "/shared/*/.git", "/shared/*/.git/*"]);
});

test("Policies ohne Variable: external_directory komplett gesperrt", () => {
  assert.deepEqual(sharedPolicies(false), [{ action: "permission", resource: "external_directory:*", effect: "deny" }]);
  const off = [BASE, {}, content(environment({}, ["llm"]))];
  for (const r of ["/shared/*", "/tmp/captain/*", "/etc/*"]) assert.ok(blocked(off, "external_directory", r), r);
  assert.equal(blocked(off, "edit", "/shared/a.txt"), false); // ohne Freigabe kommt edit gar nicht so weit
});

test("Umgebung: CAPTAIN_SHARED_DIR/CAPTAIN_HOME gehen nicht an opencode", () => {
  const env = environment({ PATH: "/bin", CAPTAIN_SHARED_DIR: "/srv/captain-shared", CAPTAIN_HOME: "/opt/captain" }, ["llm"], { shared: true });
  assert.equal(env.CAPTAIN_SHARED_DIR, undefined);
  assert.equal(env.CAPTAIN_HOME, undefined);
  assert.deepEqual(content(env).experimental.policies.slice(-sharedPolicies(true).length), sharedPolicies(true));
});

function tmpdir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), "captain-shared-"));
}
function trySymlink(target, link, type) {
  try {
    fs.symlinkSync(target, link, type);
    return true;
  } catch {
    return false; // Windows ohne Symlink-Recht
  }
}

test("Pruefung: saubere Struktur ohne Befund", async () => {
  const d = tmpdir();
  fs.mkdirSync(path.join(d, "sub", "tief"), { recursive: true });
  fs.mkdirSync(path.join(d, "repo", ".git"), { recursive: true });
  fs.writeFileSync(path.join(d, "a.txt"), "a");
  fs.writeFileSync(path.join(d, "sub", "tief", "b.md"), "b");
  fs.writeFileSync(path.join(d, "repo", ".git", "config"), "c");
  assert.deepEqual(await scan(d, { sensitive: [] }), []);
  fs.rmSync(d, { recursive: true });
});

test("Pruefung: Symlinks (auch relativ, auf Verzeichnisse) werden gemeldet", async (t) => {
  const d = tmpdir();
  fs.mkdirSync(path.join(d, "sub"));
  fs.writeFileSync(path.join(d, "a.txt"), "a");
  if (!trySymlink(os.tmpdir(), path.join(d, "sub", "dirlink"), "dir")) {
    fs.rmSync(d, { recursive: true });
    return t.skip("Symlinks hier nicht anlegbar");
  }
  trySymlink("../a.txt", path.join(d, "sub", "rel"), "file");
  const problems = await scan(d, { sensitive: [] });
  assert.equal(problems.length, 2, problems.join("\n"));
  assert.ok(problems.every((p) => p.includes("Symlink")), problems.join("\n"));
  fs.rmSync(d, { recursive: true });
});

test("Pruefung: harte Links, Captain-Daten, Grenze der Meldungen", async () => {
  const d = tmpdir();
  fs.mkdirSync(path.join(d, "sessions"));
  fs.writeFileSync(path.join(d, "a.txt"), "a");
  fs.linkSync(path.join(d, "a.txt"), path.join(d, "b.txt"));
  let problems = await scan(d, { sensitive: [] });
  assert.equal(problems.length, 2);
  assert.ok(problems.every((p) => p.includes("harten Links")), problems.join("\n"));
  problems = await scan(d, { sensitive: [path.join(d, "sessions")] });
  assert.ok(problems.some((p) => p.includes("Captain-Daten")), problems.join("\n"));
  problems = await scan(d, { sensitive: [d] }); // geteiltes Verzeichnis = Captain-Daten
  assert.equal(problems.length, 1);
  for (let i = 0; i < 30; i++) fs.linkSync(path.join(d, "a.txt"), path.join(d, `l${i}`));
  assert.equal((await scan(d, { sensitive: [], max: 20 })).length, 20);
  fs.rmSync(d, { recursive: true });
});

test("Pruefung: FIFO wird gemeldet", { skip: process.platform === "win32" }, async () => {
  const d = tmpdir();
  const { execFileSync } = await import("node:child_process");
  execFileSync("mkfifo", [path.join(d, "fifo")]);
  const problems = await scan(d, { sensitive: [] });
  assert.equal(problems.length, 1);
  assert.match(problems[0], /weder Datei noch Verzeichnis/);
  fs.rmSync(d, { recursive: true });
});

test("Mount: fehlt, keine Verzeichnis, beschreibbar -> Fehler", () => {
  const d = tmpdir();
  assert.throws(() => checkMount(path.join(d, "fehlt")), /fehlt/);
  fs.writeFileSync(path.join(d, "datei"), "x");
  assert.throws(() => checkMount(path.join(d, "datei")), /kein Verzeichnis/);
  assert.throws(() => checkMount(d), /beschreibbar/);
  assert.equal(SHARED_MOUNT, "/shared");
  fs.rmSync(d, { recursive: true });
});
