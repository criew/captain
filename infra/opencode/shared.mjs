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

// Wurzel und Systemverzeichnisse: nie als Ganzes freigeben (wie captain/shared.py)
export const SYSTEM_DIRS = new Set(["/", "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib32", "/lib64",
  "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/tmp", "/usr", "/var"]);
// Git-Innenleben in jeder Tiefe (wie captain/shared.py): .git/config kann
// Zugangsdaten in Remote-URLs enthalten. "*" passt auch auf "/".
export const GIT_PATTERNS = [`${SHARED_MOUNT}/.git`, `${SHARED_MOUNT}/.git/*`, `${SHARED_MOUNT}/*/.git`, `${SHARED_MOUNT}/*/.git/*`];
const FORBIDDEN = /[:$\\\x00-\x1f\x7f]/;

// Captain-eigene Verzeichnisse im Container: duerfen nie unter /shared auftauchen
// (z. B. CAPTAIN_SHARED_DIR=$CAPTAIN_HOME oder ein Bind-Mount darunter).
export const SENSITIVE = ["/tmp/captain", "/root/.local/share/opencode", "/etc/captain", "/run/captain", "/root/.config/opencode"];

// Host-Pfad pruefen -> kanonischer Pfad oder null (aus); wirft bei Unzulaessigem.
export function normalizeSharedDir(value) {
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
  return p;
}

// Ueberschneidung mit CAPTAIN_HOME (Sessions, opencode-DB, Config) verbieten.
export function checkHomeOverlap(shared, home) {
  const h = String(home ?? "").trim().replace(/\/+$/, "");
  if (!shared || !h.startsWith("/")) return;
  const inside = (a, b) => a === b || a.startsWith(`${b}/`);
  if (inside(shared, h) || inside(h, shared)) {
    throw new Error(`${SHARED_ENV}: ${shared} ueberschneidet sich mit CAPTAIN_HOME (${h}) – dort liegen Sessions und Datenbank`);
  }
}

// Policies (Format "<aktion>:<ressource>"): die Sicherheitsbasis enthaelt
// KEINE external_directory-Policy mehr (sie wuerde als frueheste Quelle jede
// Freigabe schlagen); die Admin-Config darf "experimental" nicht setzen.
// opencode prueft external_directory mit "<verzeichnis>/*" (bei Dateien das
// Elternverzeichnis, Pfad lexikalisch aufgeloest) – "/shared/*" trifft also
// /shared selbst und alles darunter, nicht /sharedX. Danach (gewinnt
// innerhalb derselben Quelle) .git in jeder Tiefe und edit unter /shared verboten.
export function sharedPolicies(enabled) {
  const rule = (resource, effect) => ({ action: "permission", resource, effect });
  if (!enabled) return [rule("external_directory:*", "deny")];
  return [
    rule("external_directory:*", "deny"),
    rule(`external_directory:${SHARED_MOUNT}/*`, "allow"),
    ...["external_directory", "read"].flatMap((a) => GIT_PATTERNS.map((p) => rule(`${a}:${p}`, "deny"))),
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

// Unter root alles pruefen, ohne Symlinks zu folgen. Liefert Problembeschreibungen
// (leer = in Ordnung); hoechstens `max`.
export async function scan(root = SHARED_MOUNT, { sensitive = SENSITIVE, max = 20 } = {}) {
  const ids = sensitiveIds(sensitive);
  const problems = [];
  const seen = new Set();
  const stack = [root];
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
    for (const ent of entries) {
      const p = path.posix.join(dir, ent.name);
      let s;
      try {
        s = await fs.promises.lstat(p);
      } catch (e) {
        if (e.code === "ENOENT") continue;
        problems.push(`${p}: nicht lesbar (${e.code})`);
        continue;
      }
      if (s.isSymbolicLink()) problems.push(`${p}: Symlink (nicht erlaubt)`);
      else if (s.isDirectory()) stack.push(p);
      else if (!s.isFile()) problems.push(`${p}: weder Datei noch Verzeichnis (Geraet, FIFO, Socket – nicht erlaubt)`);
      else if (s.nlink > 1) problems.push(`${p}: Datei mit ${s.nlink} harten Links (nicht erlaubt)`);
      if (problems.length >= max) break;
    }
  }
  return problems;
}
