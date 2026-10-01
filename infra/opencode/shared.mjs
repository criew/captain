// Geteiltes Verzeichnis (CAPTAIN_SHARED_DIR): nur lesend fuer alle Chats.
//
// compose.deploy.yml bindet das Host-Verzeichnis aus CAPTAIN_SHARED_DIR
// schreibgeschuetzt (:ro) unter dem festen Pfad /shared ein (leer = das leere
// Volume "shared-leer", Feature aus). Dieses Modul
//
//   - prueft den Host-Pfad (gleiche Regeln wie captain/shared.py, gemeinsame
//     Testfaelle tests/data/shared_dir.json),
//   - liefert die Policies (harte Obergrenze in OPENCODE_CONFIG_CONTENT):
//     external_directory nur fuer /shared/* erlaubt, .git (jede Tiefe) und edit
//     unter /shared verboten,
//   - prueft /shared selbst: Verzeichnis, nicht beschreibbar, und darunter
//     keine Symlinks, keine Dateien mit mehreren harten Links, keine
//     Geraete/FIFOs/Sockets und keine Captain-eigenen Verzeichnisse.
//
// Warum die Pruefung: opencode 2.0.20 loest Pfade nur lexikalisch auf
// (path.resolve, kein realpath, FileAccess.resolve im Binary) und folgt beim
// Lesen Symlinks. Ein Symlink /shared/x -> /tmp/captain/<andere-session> waere
// als "/shared/x/..." erlaubt und laese fremde Daten. Deshalb startet opencode
// mit einem solchen Eintrag nicht, und eine laufende Pruefung (alle
// SCAN_INTERVAL_MS) beendet opencode, sobald einer auftaucht (fail closed).
import fs from "node:fs";
import path from "node:path";

export const SHARED_ENV = "CAPTAIN_SHARED_DIR";
export const SHARED_MOUNT = "/shared";
export const SCAN_INTERVAL_MS = 10_000;
// Grenzen einer Pruefung (anhebbar per Variable); darueber fail closed, damit
// eine langsame Pruefung das Fenster fuer neue Symlinks nicht verlaengert.
export const LIMIT_ENV = { entries: "CAPTAIN_SHARED_MAX_ENTRIES", seconds: "CAPTAIN_SHARED_MAX_SECONDS" };
export const LIMIT_DEFAULTS = { entries: 100_000, seconds: 5 };

// Wurzel und Systemverzeichnisse: nie als Ganzes freigeben (wie captain/shared.py)
export const SYSTEM_DIRS = new Set(["/", "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib32", "/lib64",
  "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/tmp", "/usr", "/var"]);
// Git-Innenleben in jeder Tiefe (wie captain/shared.py): ".git" und
// Bare-/Mirror-Repos "x.git", alle Schreibweisen von "git" (CIFS ist
// case-insensitive, die Muster nicht). "*" passt auch auf "/" und auf nichts.
const GIT = ["g", "G"].flatMap((a) => ["i", "I"].flatMap((b) => ["t", "T"].map((c) => a + b + c)));
export const GIT_PATTERNS = GIT.flatMap((g) => [`${SHARED_MOUNT}/*.${g}`, `${SHARED_MOUNT}/*.${g}/*`]);
// Dateien mit Zugangsdaten: nie lesbar, und die Pruefung lehnt sie ab (grep
// wuerde sie sonst durchsuchen) – Vergleich ohne Gross/Klein.
export const SECRET_NAMES = [".git-credentials", ".netrc", "_netrc"];
export const SECRET_PATTERNS = SECRET_NAMES.flatMap((n) => [`${SHARED_MOUNT}/${n}`, `${SHARED_MOUNT}/*/${n}`]);
// Unter CAPTAIN_HOME ist nur dieses erste Segment erlaubt
export const HOME_SUBDIR = "shared";
const FORBIDDEN = /[:$\\\x00-\x1f\x7f]/;

// Captain-eigene Verzeichnisse im Container: duerfen nie unter /shared auftauchen
// (z. B. CAPTAIN_SHARED_DIR=$CAPTAIN_HOME oder ein Bind-Mount darunter).
export const SENSITIVE = ["/tmp/captain", "/root/.local/share/opencode", "/etc/captain", "/run/captain", "/root/.config/opencode"];

const segments = (p) => p.split("/").filter(Boolean);

// Segmentweise gegen CAPTAIN_HOME: erlaubt nur <home>/shared[/…]; verboten
// <home>, seine Vorfahren und jedes andere erste Segment. home leer = keine
// Pruefung (Test-Setup); nicht absolut -> Fehler.
export function checkHome(shared, home) {
  const raw = String(home ?? "").trim();
  if (!shared || !raw) return;
  if (!raw.startsWith("/") || segments(raw).some((s) => s === "." || s === "..")) {
    throw new Error(`CAPTAIN_HOME muss ein absoluter Pfad ohne '.'/'..' sein: ${JSON.stringify(raw)}`);
  }
  const h = segments(raw), p = segments(shared);
  const prefix = (a, b) => b.length <= a.length && b.every((s, i) => a[i] === s);
  if (!prefix(p, h)) {
    if (prefix(h, p)) throw new Error(`${SHARED_ENV}: ${shared} enthaelt CAPTAIN_HOME (${raw}) – dort liegen Sessions und Datenbank`);
    return;
  }
  if (p.length === h.length || p[h.length] !== HOME_SUBDIR) {
    throw new Error(`${SHARED_ENV}: unter CAPTAIN_HOME (${raw}) ist nur ${raw.replace(/\/+$/, "")}/${HOME_SUBDIR}[/…] erlaubt: ${shared}`);
  }
}

// Host-Pfad pruefen -> kanonischer Pfad oder null (aus); wirft bei Unzulaessigem.
export function normalizeSharedDir(value, home) {
  const raw = String(value ?? "").trim();
  if (!raw) return null;
  const fail = (msg) => { throw new Error(`${SHARED_ENV}: ${msg}: ${JSON.stringify(raw)}`); };
  if (!/^[\x00-\x7f]*$/.test(raw)) fail("nur ASCII-Pfade");
  const bad = raw.match(FORBIDDEN);
  if (bad) fail(`Zeichen ${JSON.stringify(bad[0])} nicht erlaubt`);
  if (!raw.startsWith("/")) fail("muss ein absoluter Pfad sein (/…)");
  const p = raw.replace(/\/+$/, "") || "/";
  if (p !== "/" && p.split("/").slice(1).some((s) => s === "" || s === "." || s === "..")) {
    fail("Pfad ohne '.', '..' und leere Segmente");
  }
  if (SYSTEM_DIRS.has(p)) fail(`${p} ist die Wurzel oder ein Systemverzeichnis – eigenes Unterverzeichnis nehmen`);
  checkHome(p, home);
  return p;
}

// Grenzen aus der Umgebung (positive ganze Zahlen), sonst Default; wirft bei Unsinn.
export function scanLimits(env = {}) {
  const out = {};
  for (const [k, name] of Object.entries(LIMIT_ENV)) {
    const v = String(env[name] ?? "").trim();
    if (!v) { out[k] = LIMIT_DEFAULTS[k]; continue; }
    if (!/^[1-9][0-9]{0,8}$/.test(v)) throw new Error(`${name} muss eine positive ganze Zahl sein: ${JSON.stringify(v)}`);
    out[k] = Number(v);
  }
  return { maxEntries: out.entries, maxMs: out.seconds * 1000 };
}

// Policies (Format "<aktion>:<ressource>"): die Sicherheitsbasis enthaelt
// KEINE external_directory-Policy mehr (sie wuerde als frueheste Quelle jede
// Freigabe schlagen); die Admin-Config darf "experimental" nicht setzen.
// opencode prueft external_directory mit "<verzeichnis>/*" (bei Dateien das
// Elternverzeichnis, Pfad lexikalisch aufgeloest) – "/shared/*" trifft also
// /shared selbst und alles darunter, nicht /sharedX. Danach (gewinnt
// innerhalb derselben Quelle) Git-Innenleben, Zugangsdaten und edit verboten.
export function sharedPolicies(enabled) {
  const rule = (resource, effect) => ({ action: "permission", resource, effect });
  if (!enabled) return [rule("external_directory:*", "deny")];
  return [
    rule("external_directory:*", "deny"),
    rule(`external_directory:${SHARED_MOUNT}/*`, "allow"),
    ...["external_directory", "read"].flatMap((a) => GIT_PATTERNS.map((p) => rule(`${a}:${p}`, "deny"))),
    ...SECRET_PATTERNS.map((p) => rule(`read:${p}`, "deny")),
    rule(`edit:${SHARED_MOUNT}`, "deny"),
    rule(`edit:${SHARED_MOUNT}/*`, "deny"),
  ];
}

// /shared selbst: muss ein Verzeichnis und schreibgeschuetzt (:ro) eingebunden sein.
export function checkMount(root = SHARED_MOUNT) {
  let st;
  try {
    st = fs.lstatSync(root);
  } catch (e) {
    throw new Error(`${root} fehlt (${e.code}) – compose.deploy.yml bindet CAPTAIN_SHARED_DIR dort ein`);
  }
  if (!st.isDirectory()) throw new Error(`${root} ist kein Verzeichnis`);
  let writable = true;
  try {
    fs.accessSync(root, fs.constants.W_OK);
  } catch {
    writable = false;
  }
  if (writable) throw new Error(`${root} ist beschreibbar – nur schreibgeschuetzt (:ro) einbinden`);
}

function sensitiveIds(dirs) {
  const ids = new Map();
  for (const d of dirs) {
    try {
      const st = fs.statSync(d);
      ids.set(`${st.dev}:${st.ino}`, d);
    } catch {
      // fehlt in diesem Container – nichts zu vergleichen
    }
  }
  return ids;
}

// Mountpoints unterhalb von root laut /proc/self/mountinfo (5. Feld, Oktal-
// Escapes wie "\040" fuer Leerzeichen). Bind-Mounts desselben Dateisystems haben dieselbe
// Geraetenummer und fielen beim dev-Vergleich nicht auf.
export function mountsBelow(root, mountinfo = "/proc/self/mountinfo") {
  let text;
  try {
    text = fs.readFileSync(mountinfo, "utf8");
  } catch {
    return []; // kein Linux (Unit-Tests unter Windows)
  }
  const unescape = (s) => s.replace(/\\([0-7]{3})/g, (_m, o) => String.fromCharCode(parseInt(o, 8)));
  const prefix = `${root.replace(/\/+$/, "")}/`;
  return text.split("\n").map((l) => l.split(" ")[4]).filter(Boolean).map(unescape)
    .filter((m) => m.startsWith(prefix));
}

// Bare-/Mirror-Repository: HEAD-Datei plus objects/ und refs/ (ausserhalb von .git)
function isBareRepo(names) {
  return names.has("HEAD") && names.has("objects") && names.has("refs");
}

// Unter root alles pruefen, ohne Symlinks zu folgen. Liefert Problembeschreibungen
// (leer = in Ordnung); hoechstens `max`. Mehr als maxEntries Eintraege oder
// laenger als maxMs -> Befund (fail closed).
export async function scan(root = SHARED_MOUNT, { sensitive = SENSITIVE, max = 20, maxEntries = LIMIT_DEFAULTS.entries,
  maxMs = LIMIT_DEFAULTS.seconds * 1000, mountinfo = "/proc/self/mountinfo" } = {}) {
  const ids = sensitiveIds(sensitive);
  const problems = mountsBelow(root, mountinfo).map((m) => `${m}: Mountpoint (nicht erlaubt)`);
  const seen = new Set();
  const stack = [root];
  const started = Date.now();
  let count = 0, rootDev;
  const limit = (msg) => problems.push(`${root}: ${msg} – Grenze per ${msg.includes("Eintraege") ? LIMIT_ENV.entries : LIMIT_ENV.seconds} anheben`);
  while (stack.length && problems.length < max) {
    const dir = stack.pop();
    let st;
    try {
      st = await fs.promises.lstat(dir);
    } catch (e) {
      if (e.code === "ENOENT" && dir !== root) continue; // gerade geloescht
      problems.push(`${dir}: nicht lesbar (${e.code})`);
      continue;
    }
    rootDev ??= st.dev;
    const id = `${st.dev}:${st.ino}`;
    if (ids.has(id)) { problems.push(`${dir}: ist ${ids.get(id)} (Captain-Daten)`); continue; }
    if (seen.has(id)) continue; // Schleife ueber Bind-Mounts
    seen.add(id);
    let entries;
    try {
      entries = await fs.promises.readdir(dir, { withFileTypes: true });
    } catch (e) {
      if (e.code === "ENOENT" && dir !== root) continue;
      problems.push(`${dir}: nicht lesbar (${e.code})`);
      continue;
    }
    const names = new Set(entries.map((e) => e.name));
    if (path.posix.basename(dir).toLowerCase() !== ".git" && isBareRepo(names)) {
      problems.push(`${dir}: Bare-/Mirror-Repository (nicht erlaubt, nur normale Clones)`);
    }
    for (const ent of entries) {
      if (++count > maxEntries) { limit(`mehr als ${maxEntries} Eintraege`); return problems; }
      if (Date.now() - started > maxMs) { limit(`Pruefung dauerte laenger als ${maxMs / 1000} s`); return problems; }
      const p = path.posix.join(dir, ent.name);
      let s;
      try {
        s = await fs.promises.lstat(p);
      } catch (e) {
        if (e.code === "ENOENT") continue;
        problems.push(`${p}: nicht lesbar (${e.code})`);
        continue;
      }
      if (s.dev !== rootDev) problems.push(`${p}: anderes Dateisystem bzw. Mountpoint (nicht erlaubt)`);
      else if (s.isSymbolicLink()) problems.push(`${p}: Symlink (nicht erlaubt)`);
      else if (s.isDirectory()) stack.push(p);
      else if (!s.isFile()) problems.push(`${p}: weder Datei noch Verzeichnis (Geraet, FIFO, Socket – nicht erlaubt)`);
      else if (s.nlink > 1) problems.push(`${p}: Datei mit ${s.nlink} harten Links (nicht erlaubt)`);
      else if (SECRET_NAMES.includes(ent.name.toLowerCase())) problems.push(`${p}: Zugangsdaten-Datei (nicht erlaubt)`);
      if (problems.length >= max) break;
    }
  }
  return problems;
}
