"""Konfiguration für Captain.

Werte kommen aus Umgebungsvariablen, optional ergänzt um eine JSON-Datei
(Pfad in ``CAPTAIN_CONFIG``). Die Umgebung gewinnt immer gegen die Datei.
Die JSON-Datei benutzt dieselben Schlüssel wie die Umgebung, z. B.
``{"MM_URL": "http://localhost:8065", "ALLOWED_USERS": ["alice", "bob"]}``.

Persona/Systemnachricht: ``CAPTAIN_SYSTEM_PROMPT_FILE`` (Pfad zu einer
Textdatei) hat Vorrang vor ``CAPTAIN_SYSTEM_PROMPT`` (Text); ohne beides gilt
:data:`DEFAULT_SYSTEM_PROMPT`.
"""

from __future__ import annotations

import json
import os
import posixpath
import re
from collections.abc import Mapping
from dataclasses import dataclass

from . import shared, webfetch

CONFIG_ENV = "CAPTAIN_CONFIG"

REQUIRED = ("MM_URL", "MM_BOT_TOKEN", "OPENCODE_URL")
OPTIONAL = (
    "OPENCODE_PASSWORD", "OPENCODE_MODEL", "OPENCODE_VARIANT",
    "CAPTAIN_SYSTEM_PROMPT", "CAPTAIN_SYSTEM_PROMPT_FILE", "CAPTAIN_WEBFETCH_ALLOW",
    "CAPTAIN_SHARED_DIR",
)
DEFAULTS = {
    "SESSIONS_DIR": "/tmp/captain",
    "DATA_DIR": "data",
    # Verpasste Posts höchstens so alt nachholen: Sekunden oder mit s/m/h/d; 0 = nichts
    "CATCHUP_MAX_AGE": "24h",
    # Größere Anhänge werden nicht geladen (MB, Dezimalzahl erlaubt; 0 = keine Grenze)
    "MAX_ATTACHMENT_MB": "20",
    # Vorgeschichte einer neuen Kanal-Unterhaltung (Top-Level-Erwähnung):
    # höchstens so viele Posts und Zeichen, die neuesten gewinnen.
    "HISTORY_MAX_POSTS": "50",
    "HISTORY_MAX_CHARS": "8000",
}
DEFAULT_SYSTEM_PROMPT = (
    "Du bist **Captain**, ein hilfsbereiter, sachlicher Assistent, "
    "der über Mattermost mit Menschen chattet."
)
KEYS = (*REQUIRED, *OPTIONAL, *DEFAULTS, "ALLOWED_USERS")


class ConfigError(Exception):
    """Konfiguration fehlt oder ist ungültig."""


@dataclass(frozen=True)
class Config:
    mm_url: str
    mm_bot_token: str
    opencode_url: str
    opencode_password: str | None = None
    opencode_model: str | None = None
    opencode_variant: str | None = None
    sessions_dir: str = DEFAULTS["SESSIONS_DIR"]  # Session-Verzeichnisse (Sicht von opencode)
    data_dir: str = DEFAULTS["DATA_DIR"]
    system_prompt: str = DEFAULT_SYSTEM_PROMPT  # Persona, pro Session als Systemanweisung
    allowed_users: frozenset[str] = frozenset()  # leer = alle erlaubt
    catchup_max_age: float = 24 * 3600.0  # Sekunden
    max_attachment_mb: float = 20.0  # 0 = keine Grenze
    history_max_posts: int = int(DEFAULTS["HISTORY_MAX_POSTS"])  # 0 = keine Vorgeschichte
    history_max_chars: int = int(DEFAULTS["HISTORY_MAX_CHARS"])
    # webfetch-Allowlist (kanonische URL-Präfixe); leer = webfetch aus
    webfetch_allow: tuple[str, ...] = ()
    # Geteiltes Verzeichnis (Host-Pfad, nur zur Info); gesetzt = opencode
    # liest es unter shared.MOUNT (/shared), None = aus
    shared_dir: str | None = None

    @property
    def shared(self) -> bool:
        return self.shared_dir is not None

    @property
    def max_attachment_bytes(self) -> int | None:
        return int(self.max_attachment_mb * 1024 * 1024) if self.max_attachment_mb else None

    def is_allowed(self, username: str) -> bool:
        return not self.allowed_users or username.lower() in self.allowed_users


def _read_file(path: str) -> dict:
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    except FileNotFoundError:
        raise ConfigError(f"Konfigurationsdatei fehlt: {path}") from None
    except json.JSONDecodeError as e:
        raise ConfigError(f"Konfiguration {path} ist kein gültiges JSON: {e}") from None
    if not isinstance(data, dict):
        raise ConfigError(f"Konfiguration {path} muss ein JSON-Objekt sein.")
    unknown = set(data) - set(KEYS)
    if unknown:
        raise ConfigError(f"Unbekannte Schlüssel in {path}: {', '.join(sorted(unknown))}")
    return data


_UNITS = {"": 1, "s": 1, "m": 60, "h": 3600, "d": 86400}


def parse_duration(value) -> float:
    """``"24h"``, ``"90m"``, ``"3600"``, ``3600`` → Sekunden (≥ 0)."""
    text = "" if isinstance(value, bool) else str(value).lower()
    m = re.fullmatch(r"\s*(\d+(?:\.\d+)?)\s*([smhd]?)\s*", text)
    if not m:
        raise ConfigError(f"Ungültige Dauer: {value!r} (z. B. 3600, 90m, 24h, 2d)")
    return float(m.group(1)) * _UNITS[m.group(2)]


def _parse_mb(value) -> float:
    text = "" if isinstance(value, bool) else str(value).strip().replace(",", ".")
    try:
        mb = float(text)
    except ValueError:
        mb = -1.0
    if not mb >= 0 or mb == float("inf"):
        raise ConfigError(f"MAX_ATTACHMENT_MB muss eine Zahl ≥ 0 sein: {value!r}")
    return mb


def _parse_users(value) -> frozenset[str]:
    if value is None:
        return frozenset()
    if isinstance(value, str):
        value = value.split(",")
    return frozenset(u.strip().lstrip("@").lower() for u in value if u.strip())


def _system_prompt(values: dict) -> str:
    path = values.get("CAPTAIN_SYSTEM_PROMPT_FILE")
    if path:
        try:
            with open(path, encoding="utf-8") as f:
                text = f.read().strip()
        except OSError as e:
            raise ConfigError(f"CAPTAIN_SYSTEM_PROMPT_FILE nicht lesbar: {e}") from None
    else:
        text = str(values.get("CAPTAIN_SYSTEM_PROMPT") or "").strip()
    return text or DEFAULT_SYSTEM_PROMPT


def _sessions_dir(value) -> str:
    """Absoluter Pfad, nicht die Wurzel – der Bot löscht darunter Verzeichnisse."""
    raw = str(value).strip()
    path = posixpath.normpath(raw.replace("\\", "/")).rstrip("/") if raw else ""
    absolute = posixpath.isabs(raw) or os.path.isabs(raw)
    if not absolute or not path or path.endswith(":") or posixpath.dirname(path) == path:
        raise ConfigError(f"SESSIONS_DIR muss ein absoluter Pfad unterhalb von / sein: {raw!r}")
    return path


def _webfetch(value) -> tuple[str, ...]:
    if isinstance(value, (list, tuple)):
        value = ",".join(str(v) for v in value)
    try:
        return webfetch.parse(None if value is None else str(value))
    except ValueError as e:
        raise ConfigError(str(e)) from None


def _shared(value) -> str | None:
    try:
        return shared.normalize(None if value is None else str(value))
    except ValueError as e:
        raise ConfigError(str(e)) from None


def _count(values: dict, key: str) -> int:
    """Ganzzahl >= 0 (auch aus JSON als Zahl)."""
    raw = values[key]
    try:
        value = int(str(raw).strip())
    except ValueError:
        raise ConfigError(f"{key} muss eine ganze Zahl sein: {raw!r}") from None
    if value < 0:
        raise ConfigError(f"{key} darf nicht negativ sein: {raw!r}")
    return value


def load(path: str | None = None, env: Mapping[str, str] | None = None) -> Config:
    """Lädt die Konfiguration; wirft ConfigError bei fehlenden Pflichtwerten."""
    env = os.environ if env is None else env
    path = path or env.get(CONFIG_ENV)
    values: dict = {**DEFAULTS, **(_read_file(path) if path else {})}
    for key in KEYS:
        if env.get(key):
            values[key] = env[key]

    missing = [k for k in REQUIRED if not values.get(k)]
    if missing:
        raise ConfigError(f"Pflichtwerte fehlen: {', '.join(missing)}")

    return Config(
        mm_url=str(values["MM_URL"]).rstrip("/"),
        mm_bot_token=str(values["MM_BOT_TOKEN"]),
        opencode_url=str(values["OPENCODE_URL"]).rstrip("/"),
        # Fallback: dieselbe .env wie der opencode-Container (infra/opencode/.env)
        opencode_password=values.get("OPENCODE_PASSWORD")
        or env.get("OPENCODE_SERVER_PASSWORD")
        or None,
        opencode_model=values.get("OPENCODE_MODEL") or None,
        opencode_variant=values.get("OPENCODE_VARIANT") or None,
        sessions_dir=_sessions_dir(values["SESSIONS_DIR"]),
        data_dir=str(values["DATA_DIR"]),
        system_prompt=_system_prompt(values),
        allowed_users=_parse_users(values.get("ALLOWED_USERS")),
        catchup_max_age=parse_duration(values["CATCHUP_MAX_AGE"]),
        max_attachment_mb=_parse_mb(values["MAX_ATTACHMENT_MB"]),
        history_max_posts=_count(values, "HISTORY_MAX_POSTS"),
        history_max_chars=_count(values, "HISTORY_MAX_CHARS"),
        webfetch_allow=_webfetch(values.get("CAPTAIN_WEBFETCH_ALLOW")),
        shared_dir=_shared(values.get("CAPTAIN_SHARED_DIR")),
    )
