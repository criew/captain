"""Einstiegspunkt ``python -m captain``."""

from __future__ import annotations

import logging
import os
import signal
import sys
import threading

from . import config
from .bot import Bot
from .mattermost import MattermostClient
from .opencode import OpencodeClient
from .sessions import SessionStore


def main() -> int:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO").upper(),
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
    )
    logging.getLogger("httpx").setLevel(logging.WARNING)
    try:
        cfg = config.load()
    except config.ConfigError as e:
        print(f"[Captain] {e}", file=sys.stderr)
        return 2
    logging.getLogger("captain").info(
        "Mattermost %s, opencode %s, Modell %s, Session-Verzeichnisse %s, Daten %s, webfetch %s, "
        "geteiltes Verzeichnis %s",
        cfg.mm_url, cfg.opencode_url, cfg.opencode_model or "(Standard)",
        cfg.sessions_dir, cfg.data_dir, ", ".join(cfg.webfetch_allow) or "aus",
        f"{cfg.shared_dir} → /shared (nur lesen)" if cfg.shared else "aus",
    )

    store = SessionStore(os.path.join(cfg.data_dir, "sessions.json"))
    # Ein OpencodeClient pro Prozess: sein SSE-Thread bedient alle Sessions.
    with MattermostClient(cfg.mm_url, cfg.mm_bot_token) as mm, OpencodeClient(
        cfg.opencode_url, cfg.opencode_password
    ) as oc:
        bot = Bot(cfg, mm, oc, store)

        # Der Handler setzt nur ein Event; gestoppt wird in einem eigenen Thread.
        # Direkt im Handler könnte ``bot.stop`` den unterbrochenen Thread
        # blockieren (z. B. den WebSocket-Thread, während er die Cursor-Sperre hält).
        stop_requested = threading.Event()

        def shutdown(signum, _frame):
            logging.getLogger("captain").info("Signal %s – beende", signum)
            stop_requested.set()

        def stopper():
            stop_requested.wait()
            bot.stop()

        threading.Thread(target=stopper, name="captain-stop", daemon=True).start()
        signal.signal(signal.SIGTERM, shutdown)
        signal.signal(signal.SIGINT, shutdown)
        bot.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
