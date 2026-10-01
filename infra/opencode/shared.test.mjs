// Unit-Tests fuer das geteilte Verzeichnis (shared.mjs, start.mjs):
// node --test infra/opencode/shared.test.mjs (laeuft auch ueber pytest:
// tests/test_start_script.py). Verhalten gegen echtes opencode:
// tests/test_shared_integration.py.
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

import { checkHome, checkMount, GIT_PATTERNS, normalizeSharedDir, scan, scanLimits, SHARED_MOUNT, sharedPolicies } from "./shared.mjs";
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

test("CAPTAIN_HOME: nur <home>/shared[/…] (gemeinsame Testfaelle wie captain/shared.py)", () => {
  for (const c of CASES.home) {
    const label = `${c.home}|${c.path}`;
    if (c.ok) assert.equal(normalizeSharedDir(c.path, c.home), c.path.replace(/\/+$/, ""), label);
    else assert.throws(() => normalizeSharedDir(c.path, c.home), /CAPTAIN_SHARED_DIR|CAPTAIN_HOME/, label);
  }
  checkHome(null, "relativ"); // Feature aus: nichts zu pruefen
  assert.throws(() => checkHome("/srv/x", "relativ"), /CAPTAIN_HOME/);
});

test("Grenzen der Pruefung: Default, anhebbar, Unsinn -> Fehler", () => {
  assert.deepEqual(scanLimits({}), { maxEntries: 100000, maxMs: 5000 });
  assert.deepEqual(scanLimits({ CAPTAIN_SHARED_MAX_ENTRIES: "500000", CAPTAIN_SHARED_MAX_SECONDS: "20" }), { maxEntries: 500000, maxMs: 20000 });
  for (const v of ["0", "-1", "1e5", "abc", "1.5"]) {
    assert.throws(() => scanLimits({ CAPTAIN_SHARED_MAX_ENTRIES: v }), /CAPTAIN_SHARED_MAX_ENTRIES/, v);
    assert.throws(() => scanLimits({ CAPTAIN_SHARED_MAX_SECONDS: v }), /CAPTAIN_SHARED_MAX_SECONDS/, v);
  }
});

test("Policies: external_directory nur fuer /shared/*, .git und edit gesperrt", () => {
  const on = [BASE, {}, content(environment({}, ["llm"], { shared: true }))];
  for (const r of ["/shared/*", "/shared/sub/*", "/shared/Team Infos/*", "/shared/.github/*", "/shared/a.gitx/*"]) {
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
  assert.equal(GIT_PATTERNS.length, 16); // 8 Schreibweisen von git, je Datei/Verzeichnis und darunter
  for (const r of ["/shared/mirror.git/*", "/shared/a/.GIT/*", "/shared/a/.Git/*"]) assert.ok(blocked(on, "external_directory", r), r);
  for (const r of ["/shared/.netrc", "/shared/a/_netrc", "/shared/a/b/.git-credentials", "/shared/x.git/config"]) {
    assert.ok(blocked(on, "read", r), r);
  }
  assert.equal(blocked(on, "read", "/shared/netrc.md"), false);
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

test("Pruefung: Zugangsdaten-Dateien und Bare-Repos werden gemeldet, .git eines Clones nicht", async () => {
  const d = tmpdir();
  for (const sub of ["clone/.git/objects", "clone/.git/refs", "mirror.git/objects", "mirror.git/refs"]) {
    fs.mkdirSync(path.join(d, sub), { recursive: true });
  }
  fs.writeFileSync(path.join(d, "clone", ".git", "HEAD"), "ref");
  fs.writeFileSync(path.join(d, "mirror.git", "HEAD"), "ref");
  fs.writeFileSync(path.join(d, ".NETRC"), "machine x");
  fs.writeFileSync(path.join(d, "clone", ".git-credentials"), "https://u:p@x");
  const problems = await scan(d, { sensitive: [] });
  assert.equal(problems.length, 3, problems.join("; "));
  assert.ok(problems.some((p) => p.includes("mirror.git") && p.includes("Bare")), problems.join("; "));
  assert.equal(problems.filter((p) => p.includes("Zugangsdaten")).length, 2, problems.join("; "));
  fs.rmSync(d, { recursive: true });
});

test("Pruefung: zu viele Eintraege oder zu lange -> Befund (fail closed)", async () => {
  const d = tmpdir();
  for (let i = 0; i < 5; i++) fs.writeFileSync(path.join(d, `f${i}`), "x");
  assert.deepEqual(await scan(d, { sensitive: [], maxEntries: 5 }), []);
  let problems = await scan(d, { sensitive: [], maxEntries: 4 });
  assert.equal(problems.length, 1);
  assert.match(problems[0], /mehr als 4 Eintraege.*CAPTAIN_SHARED_MAX_ENTRIES/);
  problems = await scan(d, { sensitive: [], maxMs: -1 });
  assert.match(problems[0], /laenger als.*CAPTAIN_SHARED_MAX_SECONDS/);
  fs.rmSync(d, { recursive: true });
});

test("Pruefung: Mountpoint unter dem Verzeichnis wird gemeldet", { skip: !fs.existsSync("/dev/shm") || process.platform !== "linux" }, async () => {
  // /dev ist ein eigenes Dateisystem, /dev/shm und /dev/pts sind darin eingehaengt
  const problems = await scan("/dev", { sensitive: [], max: 100 });
  assert.ok(problems.some((p) => p.startsWith("/dev/shm:") && p.includes("Mountpoint")), problems.join("; "));
});

test("Pruefung: Mountpoints laut mountinfo (auch Bind-Mounts desselben Dateisystems)", async () => {
  const d = tmpdir();
  const info = path.join(d, "mountinfo");
  fs.writeFileSync(info, [
    "1 0 8:1 / / rw - ext4 /dev/sda1 rw",
    "2 1 8:1 /srv/captain-shared /shared ro - ext4 /dev/sda1 rw",
    "3 2 8:1 /opt/captain/sessions/ses_x /shared/infos/sitzung ro - ext4 /dev/sda1 rw",
    "4 2 8:1 /root/.ssh /shared/mit\\040leer ro - ext4 /dev/sda1 rw",
    "5 1 8:1 /x /sharedX ro - ext4 /dev/sda1 rw",
  ].join("\n") + "\n");
  const { mountsBelow } = await import("./shared.mjs");
  assert.deepEqual(mountsBelow("/shared", info), ["/shared/infos/sitzung", "/shared/mit leer"]);
  const problems = await scan(d, { sensitive: [], mountinfo: info });
  assert.deepEqual(problems, []); // d selbst hat keine Mounts darunter
  fs.rmSync(d, { recursive: true });
});
