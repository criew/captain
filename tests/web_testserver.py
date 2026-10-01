"""Kleiner HTTP-Testserver für webfetch-Tests (nur für Tests).

Ein Server, mehrere Hostnamen: Der opencode-Container bekommt per
``--add-host <name>:host-gateway`` beliebige Namen (erlaubter Host, fremder
Host, ``<erlaubt>.evil.test`` …), die alle hier landen. Unterschieden wird
über den ``Host``-Header; jede Anfrage steht in :attr:`requests`.

- ``/redirect?to=<url>`` → ``302`` auf ``<url>``
- sonst ``200 text/plain`` mit ``WEBTEST host=<host> path=<pfad>``
"""

from __future__ import annotations

import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class WebServer:
    def __init__(self, host: str = "0.0.0.0", port: int = 0):
        self.requests: list[tuple[str, str]] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # noqa: A003
                pass

            def do_GET(self):  # noqa: N802
                host = self.headers.get("Host", "")
                outer.requests.append((host, self.path))
                url = urllib.parse.urlsplit(self.path)
                if url.path == "/redirect":
                    target = urllib.parse.parse_qs(url.query).get("to", [""])[0]
                    self.send_response(302)
                    self.send_header("Location", target)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                body = f"WEBTEST host={host} path={self.path}".encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        self.server = ThreadingHTTPServer((host, port), Handler)
        self.port = self.server.server_port
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def hosts(self) -> list[str]:
        """Host-Header aller Anfragen ohne Port."""
        return [h.rsplit(":", 1)[0] for h, _ in self.requests]

    def close(self):
        self.server.shutdown()
        self.server.server_close()
