"""Session-Store: Session-Key -> opencode-Session plus Einstellungen.

Session-Keys:
  ``dm:<channel_id>``  Direktnachricht
  ``ch:<channel_id>``  Gruppen-DM/Kanal – nur noch Kanal-Einstellungen (Modell),
                       Unterhaltungen laufen dort in Threads
  ``th:<root_id>``     Thread (egal in welchem Kanal)

Welcher Key für einen Post gilt, entscheidet :mod:`captain.routing`.

Eintrag: ``{opencode_session_id, directory, model, variant}``; fehlende
Felder werden weggelassen. Modell und Variante hängen am Key, nicht an der
Session – ``reset`` behält sie. ``directory`` ist ``<SESSIONS_DIR>/<opencode_session_id>``
(z. B. ``/tmp/captain/ses_…``); die ID vergibt der Bot (:func:`new_session_id`).

Der Store hält die Daten im Speicher und schreibt nach jeder Änderung
atomar (Temp-Datei + ``os.replace``) als JSON.
"""

from __future__ import annotations

import json
import logging
import os
import tempfile
import threading
import uuid
import weakref

log = logging.getLogger(__name__)

SESSION_FIELDS = ("opencode_session_id", "directory")
SETTING_FIELDS = ("model", "variant")


def session_key(channel_type: str, channel_id: str, root_id: str | None = None) -> str:
    """Key aus Mattermost-Kanaltyp (D/G/O/P), Kanal-ID und optional Thread-Root."""
    if root_id:
        return f"th:{root_id}"
    if channel_type == "D":
        return f"dm:{channel_id}"
    return f"ch:{channel_id}"


def new_session_id() -> str:
    """Neue, eindeutige opencode-Session-ID (``ses_<32 hex>``).

    Der Bot vergibt die ID selbst (opencode v2 akzeptiert eigene IDs mit
    Präfix ``ses``), damit das Session-Verzeichnis ``<SESSIONS_DIR>/<id>``
    schon vor dem Anlegen der Session feststeht. Nur ``[a-z0-9_]`` – sicher
    als Verzeichnisname.
    """
    return f"ses_{uuid.uuid4().hex}"


def environment_prompt(directory: str) -> str:
    """Systemanweisung zur Arbeitsumgebung einer Session (mit konkretem Pfad)."""
    return (
        "# Arbeitsumgebung\n"
        f"- Dein Arbeitsverzeichnis ist `{directory}`. Du darfst ausschließlich "
        "Dateien in diesem Verzeichnis lesen, anlegen, bearbeiten und durchsuchen "
        "(Tools read, write, edit, glob, grep). Zugriffe außerhalb werden abgelehnt.\n"
        "- Du hast **keine** Shell, keinen Internet-Zugriff (kein Webfetch, keine "
        "Websuche), keine Subagenten, Skills oder Rückfrage-Dialoge. Bitte, die so "
        "etwas verlangen, beantwortest du ohne diese Werkzeuge oder sagst kurz, dass "
        "es nicht geht.\n"
        "- Anhänge aus dem Chat liegen in diesem Verzeichnis. Beim Neustart der "
        "Unterhaltung (`!neu`) wird es geleert."
    )


def session_instructions(persona: str, directory: str) -> dict[str, str]:
    """Systemanweisungen pro Session in Reihenfolge: Persona, dann Arbeitsumgebung."""
    entries = {"captain-persona": persona.strip(), "captain-umgebung": environment_prompt(directory)}
    return {k: v for k, v in entries.items() if v}


# Schwache Werte: ein Lock lebt, solange ihn jemand hält oder hält-bereit hat.
# So wachsen die Locks für kurzlebige ``th:*``-Keys nicht unbegrenzt.
_channel_locks: weakref.WeakValueDictionary[str, threading.Lock] = weakref.WeakValueDictionary()
_channel_locks_mutex = threading.Lock()


def channel_lock(key: str) -> threading.Lock:
    """Ein Lock pro Session-Key: Nachrichten derselben Session laufen seriell.

    Aufrufer müssen die Referenz halten, solange sie den Lock benutzen
    (``with channel_lock(key):`` tut das automatisch).
    """
    with _channel_locks_mutex:
        lock = _channel_locks.get(key)
        if lock is None:
            lock = _channel_locks[key] = threading.Lock()
        return lock


class SessionStore:
    def __init__(self, path: str):
        self.path = path
        self._lock = threading.Lock()
        self._data: dict[str, dict] = self._load()

    def _load(self) -> dict[str, dict]:
        try:
            with open(self.path, encoding="utf-8") as f:
                data = json.load(f)
        except FileNotFoundError:
            return {}
        except json.JSONDecodeError as e:
            broken = self.path + ".corrupt"
            log.warning("Session-Datei %s unlesbar (%s), verschoben nach %s", self.path, e, broken)
            os.replace(self.path, broken)
            return {}
        if not isinstance(data, dict):
            return {}
        return {k: v for k, v in data.items() if isinstance(v, dict)}

    def _commit(self, data: dict[str, dict]) -> None:
        """Schreibt ``data`` und übernimmt es erst danach in den Speicher."""
        self._save(data)
        self._data = data

    def _copy(self) -> dict[str, dict]:
        return {k: dict(v) for k, v in self._data.items()}

    def _save(self, data: dict[str, dict]) -> None:
        directory = os.path.dirname(os.path.abspath(self.path))
        os.makedirs(directory, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=directory, prefix=".sessions-", suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, sort_keys=True)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp, self.path)
        except BaseException:
            try:
                os.unlink(tmp)
            except FileNotFoundError:
                pass
            raise

    def get(self, key: str) -> dict | None:
        """Kopie des Eintrags oder None."""
        with self._lock:
            entry = self._data.get(key)
            return dict(entry) if entry is not None else None

    def set(self, key: str, opencode_session_id: str, directory: str) -> dict:
        """Setzt die opencode-Session des Keys; Einstellungen bleiben erhalten."""
        with self._lock:
            data = self._copy()
            entry = data.setdefault(key, {})
            entry.update(opencode_session_id=opencode_session_id, directory=directory)
            self._commit(data)
            return dict(entry)

    def reset(self, key: str) -> None:
        """Vergisst die Session des Keys (z. B. /new), behält Modell und Variante."""
        with self._lock:
            if key not in self._data:
                return
            data = self._copy()
            entry = data[key]
            for field in SESSION_FIELDS:
                entry.pop(field, None)
            if not entry:
                del data[key]
            self._commit(data)

    def set_setting(self, key: str, **values: str | None) -> dict:
        """Setzt ``model``/``variant``; ``None`` entfernt den Wert."""
        unknown = set(values) - set(SETTING_FIELDS)
        if unknown:
            raise ValueError(f"Unbekannte Einstellung: {', '.join(sorted(unknown))}")
        with self._lock:
            data = self._copy()
            entry = data.setdefault(key, {})
            for name, value in values.items():
                if value is None:
                    entry.pop(name, None)
                else:
                    entry[name] = value
            result = dict(entry)
            if not entry:
                del data[key]
            self._commit(data)
            return result
