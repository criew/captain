"""Wann antwortet Captain, wohin und in welcher Session? (reine Funktionen)

=====================================  ==========================  ==================
Ort                                    Reaktion                    Antwort
=====================================  ==========================  ==================
DM mit Captain (``D``)                 jede Nachricht              direkt im Chat
Kanal/Gruppe (``O``/``P``/``G``),      nur bei ``@captain``        Thread unter dem
Top-Level                                                          Post
Thread mit Captain-Beteiligung         jede Nachricht              im Thread
Thread ohne Captain-Beteiligung        nur bei ``@captain``        im Thread
=====================================  ==========================  ==================

Alles hier ist ohne Netz testbar; ob Captain in einem Thread schon geschrieben
hat, ermittelt der Aufrufer (:meth:`captain.bot.Bot.addressed`).
"""

from __future__ import annotations

import re
from collections.abc import Callable

from .mattermost import Post
from .sessions import session_key

HISTORY_POSTS = 50  # Vorgeschichte einer neuen Kanal-Unterhaltung: höchstens so viele Posts
HISTORY_CHARS = 8000  # … und so viele Zeichen (neueste gewinnen); Betrieb: HISTORY_MAX_*
HISTORY_POST_CHARS = 2000  # einzelner Post wird darüber gekürzt


def _mention_re(username: str) -> re.Pattern[str]:
    # @captain, @Captain, „@captain,“ – aber nicht @captain-x, @captain.x, mail@captain
    return re.compile(
        rf"(?<![\w@.-])@{re.escape(username)}(?![\w-]|\.\w)", re.IGNORECASE
    )


_CODE_RE = re.compile(r"```.*?(?:```|\Z)|`[^`\n]*`", re.DOTALL)


def _mentions(text: str, bot_username: str) -> list[re.Match[str]]:
    """``@name``-Treffer außerhalb von Code (```Block``` und `inline`)."""
    if not bot_username:
        return []
    code = [m.span() for m in _CODE_RE.finditer(text)]
    return [
        m for m in _mention_re(bot_username).finditer(text)
        if not any(a <= m.start() < b for a, b in code)
    ]


def is_mention(post: Post, bot_id: str, bot_username: str) -> bool:
    """Erwähnt der Post den Bot ausdrücklich mit ``@name`` (Groß-/Kleinschreibung egal)?

    Nur der Text zählt, nicht ``mentions`` aus dem Event: In Gruppen-DMs
    enthält das alle Mitglieder, in Kanälen auch die Empfänger von
    ``@channel``/``@all``/``@here``. ``@name`` in Code zählt nicht.
    """
    return bool(_mentions(post.message, bot_username))


def strip_mention(text: str, bot_username: str, display: str = "Captain") -> str:
    """Text für Befehle und Prompt: Anrede ``@captain`` am Anfang entfällt,
    weitere Erwähnungen werden zu ``display`` („Frag @captain.“ → „Frag Captain.“).

    Ohne Erwähnung bleibt der Text bis auf Leerraum an den Enden unverändert
    (Einrückungen, Tabellen, Code).
    """
    found = _mentions(text, bot_username)
    if not found:
        return text.strip()
    out: list[str] = []
    pos = 0
    for m in found:
        out.append(text[pos:m.start()])
        pos = m.end()
        if not "".join(out).strip():  # Anrede am Anfang: samt „,“/„:“ weg
            out = []
            rest = re.match(r"[ \t]*[,:;]?[ \t]*", text[pos:])
            pos += rest.end()
        else:
            out.append(display)
    out.append(text[pos:])
    return "".join(out).strip()


def should_answer(post: Post, *, bot_id: str, bot_username: str, in_thread: bool = False) -> bool:
    """Soll Captain auf ``post`` reagieren?

    ``in_thread``: Captain hat im Thread ``post.root_id`` schon geschrieben
    (nur relevant für Thread-Antworten außerhalb von DMs). Eigene Posts und
    Posts von Bots/Webhooks werden nie beantwortet.
    """
    if post.from_bot or (bot_id and post.user_id == bot_id):
        return False
    if post.channel_type == "D":
        return True
    if is_mention(post, bot_id, bot_username):
        return True
    return bool(post.root_id) and in_thread


def reply_root(post: Post) -> str | None:
    """Thread, in dem die Antwort erscheint; ``None`` = direkt im Chat (DM)."""
    if post.root_id:
        return post.root_id
    if post.channel_type == "D":
        return None
    return post.id  # Top-Level in Kanal/Gruppe → Thread unter dem Post öffnen


def conversation_key(post: Post) -> str:
    """Session-Key: ``dm:<channel>`` für DMs, sonst ``th:<root>``."""
    return session_key(post.channel_type, post.channel_id, reply_root(post))


def settings_key(post: Post) -> str:
    """Wo ``!modell`` gilt: Top-Level in Kanal/Gruppe → ganzer Kanal, sonst die Session."""
    if post.channel_type != "D" and not post.root_id:
        return f"ch:{post.channel_id}"
    return conversation_key(post)


def channel_history(
    posts: list[dict],
    *,
    bot_id: str,
    before: int,
    exclude: set[str] = frozenset(),
    is_bot: Callable[[str], bool] = lambda _uid: False,
    name_of: Callable[[str], str] = lambda uid: uid,
    max_posts: int = HISTORY_POSTS,
    max_chars: int = HISTORY_CHARS,
    post_chars: int = HISTORY_POST_CHARS,
) -> list[dict]:
    """Kanal-Posts seit Captains letzter Beteiligung, älteste zuerst.

    ``posts``: Kanal-Posts (Top-Level und Thread-Antworten, beliebige
    Reihenfolge) wie von ``GET /channels/{id}/posts``. Letzte Beteiligung ist
    der jüngste Thread-Root, in dem Captain geschrieben hat (bzw. ein eigener
    Top-Level-Post): Alles bis dahin kannte Captain schon. Liefert nur
    Top-Level-Posts von Menschen, strikt danach und vor ``before``
    (``create_at`` in ms), ohne ``exclude``. Gedeckelt auf ``max_posts`` und
    ``max_chars`` (Zeilen ``Name: Text`` inkl. Zeilenumbruch) – die neuesten
    gewinnen; ``message`` ist ggf. gekürzt, ``name`` kommt aus ``name_of``.
    """
    by_id = {p.get("id"): p for p in posts}
    cutoff = 0
    for p in posts:
        if p.get("user_id") != bot_id:
            continue
        root = p.get("root_id")
        if root:
            # Root außerhalb des Fensters ist älter als alles hier → 0
            cutoff = max(cutoff, (by_id.get(root) or {}).get("create_at", 0))
        else:
            cutoff = max(cutoff, p.get("create_at", 0))
    candidates = [
        p for p in posts
        if not p.get("root_id")
        and not p.get("type")
        and not p.get("delete_at")
        and p.get("user_id") != bot_id
        and p.get("id") not in exclude
        and cutoff < p.get("create_at", 0) < before
        and (p.get("message") or "").strip()
        and not _props_bot(p)
        and not is_bot(p.get("user_id", ""))
    ]
    candidates.sort(key=lambda p: p.get("create_at", 0))
    picked: list[dict] = []
    total = 0
    for p in reversed(candidates):
        if len(picked) >= max_posts:
            break
        message = p["message"].strip()
        if len(message) > post_chars:
            message = message[:post_chars] + " […]"
        name = name_of(p.get("user_id", "")) or "?"
        cost = len(name) + 2 + len(message) + 1  # „Name: Text\n“
        if total + cost > max_chars:
            break
        total += cost
        picked.append({**p, "message": message, "name": name})
    picked.reverse()
    return picked


def _props_bot(post: dict) -> bool:
    props = post.get("props") or {}
    return isinstance(props, dict) and any(
        str(props.get(k, "")).lower() == "true" for k in ("from_bot", "from_webhook")
    )
