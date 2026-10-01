"""Nachhol-Zustand: Cursor pro Kanal und beantwortete Posts.

Grundlage fürs Nachholen verpasster Posts nach einem Neustart oder einer
WebSocket-Lücke (siehe :mod:`captain.catchup`). Datei ``cursors.json`` in
``DATA_DIR``::

    {"version": 2, "initialized": true,
     "channels": {"<channel_id>": 1790798700148, ...},
     "answered": {"<post_id>": 1790798701234, ...}}

Cursor (Low-Watermark)
----------------------
Gespeichert wird pro Kanal ``min(neuester gesehener Post, ältester offener
Post − 1)``. *Offen* ist ein an Captain gerichteter Post, der angenommen, aber
noch nicht fertig beantwortet ist (Warteschlange, Nachschlagen, Streaming).
Stirbt der Bot vorher, bleibt der Cursor davor stehen und der Post wird beim
nächsten Start nachgeholt. Zusätzlich lassen sich Kanäle **einfrieren**
(Nachholen fehlgeschlagen): Ihr Cursor bleibt dann, wo er war, auch wenn live
weitere Posts kommen – die Lücke wird beim nächsten Versuch geschlossen.

Beantwortete Posts
------------------
Post-IDs fertig beantworteter Posts (``answered``, mit Zeitpunkt) werden
``answered_ttl`` Sekunden lang gespeichert und beim Nachholen übersprungen –
so bleibt ein Post, den der Low-Watermark erneut liefert, bei genau einer
Antwort.

Erster Start
------------
Solange :attr:`initialized` falsch ist (keine Datei), wird **nichts**
gespeichert; erst :meth:`set_all` (alle Kanäle auf „jetzt“) setzt den Marker.
Ein fehlgeschlagener erster Start hinterlässt also keine halbe Datei, die beim
nächsten Start alle übrigen Kanäle als „neu“ erscheinen ließe.

Gespeichert wird atomar (Temp-Datei + ``os.replace``) und gedrosselt
(höchstens alle ``interval`` Sekunden, Timer-Thread); :meth:`close` schreibt
den Rest.
"""

from __future__ import annotations

import json
import logging
import os
import tempfile
import threading
import time
from collections.abc import Callable, Iterable

log = logging.getLogger(__name__)

VERSION = 2


def _now_ms() -> int:
    return int(time.time() * 1000)


def _ints(raw) -> dict[str, int]:
    if not isinstance(raw, dict):
        return {}
    return {
        k: int(v) for k, v in raw.items()
        if isinstance(v, (int, float)) and not isinstance(v, bool)
    }


class CursorStore:
    def __init__(
        self,
        path: str,
        *,
        interval: float = 2.0,
        answered_ttl: float = 24 * 3600.0,
        clock: Callable[[], int] = _now_ms,
    ):
        self.path = path
        self.interval = interval
        self.answered_ttl_ms = int(answered_ttl * 1000)
        self._clock = clock
        self._lock = threading.Lock()
        self._save_lock = threading.Lock()
        persisted, answered, self.initialized = self._load()
        self._persisted: dict[str, int] = persisted  # zuletzt geschrieben
        self._known: set[str] = set(persisted)  # Kanäle mit gültigem Cursor
        self._seen: dict[str, int] = dict(persisted)  # neuester gesehener Post
        self._open: dict[str, dict[str, int]] = {}  # Kanal → {Post-ID: create_at}
        self._frozen: dict[str, int] = {}  # Kanal → Obergrenze
        self._frozen_all = False
        self._answered: dict[str, int] = answered  # Post-ID → beantwortet um (ms)
        self._dirty = False
        self._last_save = time.monotonic()  # erster gedrosselter Schreibvorgang nach interval
        self._timer: threading.Timer | None = None
        self._closed = False

    def _load(self) -> tuple[dict[str, int], dict[str, int], bool]:
        try:
            with open(self.path, encoding="utf-8") as f:
                raw = json.load(f)
        except FileNotFoundError:
            return {}, {}, False
        except (json.JSONDecodeError, UnicodeDecodeError) as e:
            broken = self.path + ".corrupt"
            log.warning("Cursor-Datei %s unlesbar (%s), verschoben nach %s", self.path, e, broken)
            os.replace(self.path, broken)
            return {}, {}, False
        if not isinstance(raw, dict) or not isinstance(raw.get("channels"), dict):
            log.warning("Cursor-Datei %s ohne gültiges „channels“ – wie erster Start", self.path)
            return {}, {}, False
        # Version 1 kannte den Marker nicht, war aber nur nach einem Start vorhanden
        initialized = bool(raw.get("initialized", raw.get("version") == 1))
        return _ints(raw["channels"]), _ints(raw.get("answered")), initialized

    # -- Lesen ----------------------------------------------------------------

    def get(self, channel_id: str) -> int | None:
        """Cursor zum Nachholen (``None`` = Kanal unbekannt)."""
        with self._lock:
            if channel_id not in self._known:
                return None
            return self._effective(channel_id)

    def snapshot(self) -> dict[str, int]:
        """Was jetzt gespeichert würde."""
        with self._lock:
            return self._channels_to_save()

    def is_answered(self, post_id: str) -> bool:
        with self._lock:
            return post_id in self._answered

    def open_posts(self, channel_id: str) -> dict[str, int]:
        with self._lock:
            return dict(self._open.get(channel_id, {}))

    def _effective(self, channel_id: str) -> int:
        """Aufrufer hält ``_lock``."""
        value = self._seen.get(channel_id, 0)
        pending = self._open.get(channel_id)
        if pending:
            value = min(value, min(pending.values()) - 1)
        if channel_id in self._frozen:
            value = min(value, self._frozen[channel_id])
        if self._frozen_all and channel_id in self._persisted:
            value = min(value, self._persisted[channel_id])
        return max(value, 0)

    def _channels_to_save(self) -> dict[str, int]:
        if self._frozen_all:
            return dict(self._persisted)
        return {ch: self._effective(ch) for ch in self._known}

    # -- Schreiben ------------------------------------------------------------

    def advance(self, channel_id: str, create_at: int) -> None:
        """Neuester gesehener Post (nie zurück)."""
        if not channel_id or not create_at:
            return
        with self._lock:
            if create_at <= self._seen.get(channel_id, 0):
                return
            self._seen[channel_id] = int(create_at)
            self._changed()

    def hold(self, channel_id: str, post_id: str, create_at: int) -> None:
        """Adressierter Post angenommen: Cursor bleibt davor, bis :meth:`release`."""
        if not channel_id or not post_id:
            return
        with self._lock:
            self._open.setdefault(channel_id, {})[post_id] = int(create_at)
            self._changed()

    def release(self, channel_id: str, post_id: str, *, answered: bool = True) -> None:
        """Post erledigt; ``answered`` = fertig beantwortet (wird gemerkt)."""
        with self._lock:
            pending = self._open.get(channel_id)
            if pending is not None:
                pending.pop(post_id, None)
                if not pending:
                    del self._open[channel_id]
            if answered and post_id:
                self._answered[post_id] = self._clock()
            self._changed()

    def mark_answered(self, post_id: str) -> None:
        with self._lock:
            self._answered[post_id] = self._clock()
            self._changed()

    def mark_known(self, channel_id: str, start: int | None = None) -> None:
        """Kanal hat ab jetzt einen gültigen Cursor (``start``: gesehen bis)."""
        with self._lock:
            self._known.add(channel_id)
            if start is not None and start > self._seen.get(channel_id, 0):
                self._seen[channel_id] = int(start)
            self._changed()

    def freeze(self, channel_id: str, at: int) -> None:
        """Cursor des Kanals bleibt höchstens ``at``, bis :meth:`unfreeze`."""
        with self._lock:
            self._frozen[channel_id] = min(self._frozen.get(channel_id, at), at)

    def unfreeze(self, channel_id: str) -> None:
        with self._lock:
            self._frozen.pop(channel_id, None)
            self._changed()

    def freeze_all(self) -> None:
        """Nichts Neues an Cursorn schreiben (Kanalliste nicht ladbar)."""
        with self._lock:
            self._frozen_all = True

    def unfreeze_all(self) -> None:
        with self._lock:
            self._frozen_all = False
            self._changed()

    @property
    def frozen(self) -> set[str]:
        with self._lock:
            return set(self._frozen)

    def set_all(self, channel_ids: Iterable[str], create_at: int) -> None:
        """Erster Start: alle Kanäle auf ``create_at``, Marker setzen, sofort schreiben."""
        with self._lock:
            for cid in channel_ids:
                self._known.add(cid)
                self._seen[cid] = max(self._seen.get(cid, 0), int(create_at))
            self.initialized = True
            self._frozen_all = False
            self._dirty = True
        self.flush()

    def _changed(self) -> None:
        """Speichern einplanen (Aufrufer hält ``_lock``)."""
        self._dirty = True
        if self._timer is not None or self._closed or not self.initialized:
            return
        wait = max(0.0, self.interval - (time.monotonic() - self._last_save))
        self._timer = threading.Timer(wait, self._timed_flush)
        self._timer.daemon = True
        self._timer.start()

    def _timed_flush(self) -> None:
        with self._lock:
            self._timer = None
        self.flush()

    def flush(self) -> None:
        """Ungespeicherte Änderungen sofort schreiben (nicht vor dem ersten Start)."""
        with self._save_lock:  # Platten-I/O ohne ``_lock``: advance() blockiert nicht
            with self._lock:
                if not self._dirty or not self.initialized:
                    return
                oldest = self._clock() - self.answered_ttl_ms
                self._answered = {k: v for k, v in self._answered.items() if v >= oldest}
                channels = self._channels_to_save()
                data = {
                    "version": VERSION,
                    "initialized": True,
                    "channels": channels,
                    "answered": dict(self._answered),
                }
                self._dirty = False
            try:
                self._save(data)
            except OSError:
                with self._lock:
                    self._dirty = True
                log.exception("Cursor-Datei %s nicht schreibbar", self.path)
                return
            with self._lock:
                self._persisted = channels
                self._last_save = time.monotonic()

    def close(self) -> None:
        with self._lock:
            self._closed = True
            timer, self._timer = self._timer, None
        if timer is not None:
            timer.cancel()
        self.flush()

    def _save(self, data: dict) -> None:
        directory = os.path.dirname(os.path.abspath(self.path))
        os.makedirs(directory, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=directory, prefix=".cursors-", suffix=".tmp")
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
