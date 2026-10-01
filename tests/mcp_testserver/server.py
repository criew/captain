"""Minimaler Remote-MCP-Server (Streamable HTTP, nur Standardbibliothek).

Nur für Tests: zeigt, dass ein freigegebenes MCP-Tool im Chat funktioniert und
ein nicht freigegebenes gesperrt bleibt. Zwei Tools:

- ``wuerfeln``: würfelt ``anzahl`` sechsseitige Würfel (Default 1).
- ``geheim``: liefert ein Geheimnis – darf in Tests **nie** aufgerufen werden.

Jeder ``tools/call`` wird geloggt (``MCP-CALL <tool> <args>``), damit sich
nachweisen lässt, welche Tools tatsächlich liefen.

Protokoll: JSON-RPC per ``POST /mcp``, Antwort als ``application/json``
(zulässige Variante von Streamable HTTP), ohne Session-State; ``GET`` (SSE)
wird mit 405 abgelehnt. Ist ``MCP_TOKEN`` (bzw. ``MCP_TEST_TOKEN``) gesetzt, muss jede Anfrage
``Authorization: Bearer <MCP_TOKEN>`` mitbringen, sonst 401.

Start: ``python server.py`` (Port ``MCP_PORT``, Default 8000) oder
``serve(port)`` aus Tests.
"""

from __future__ import annotations

import json
import logging
import os
import random
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

log = logging.getLogger("mcp_testserver")

SECRET = "GEHEIMNIS-7F3A"
PROTOCOL = "2025-06-18"
TOOLS = [
    {
        "name": "wuerfeln",
        "description": "Würfelt sechsseitige Würfel und liefert die Augenzahlen.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "anzahl": {"type": "integer", "minimum": 1, "maximum": 10,
                           "description": "Anzahl der Würfel (Default 1)"},
            },
        },
    },
    {
        "name": "geheim",
        "description": "Liefert ein streng geheimes Passwort.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]

calls: list[tuple[str, dict]] = []  # (tool, args) aller Aufrufe, für Tests


def _call(name: str, args: dict) -> dict:
    calls.append((name, args))
    log.warning("MCP-CALL %s %s", name, json.dumps(args, ensure_ascii=False))
    if name == "wuerfeln":
        n = max(1, min(10, int(args.get("anzahl") or 1)))
        wurf = [random.randint(1, 6) for _ in range(n)]
        text = f"Gewürfelt (Testserver): {', '.join(map(str, wurf))}"
        return {"content": [{"type": "text", "text": text}], "isError": False}
    if name == "geheim":
        return {"content": [{"type": "text", "text": SECRET}], "isError": False}
    return {"content": [{"type": "text", "text": f"Unbekanntes Tool: {name}"}], "isError": True}


def handle(msg: dict) -> dict | None:
    """Eine JSON-RPC-Nachricht → Antwort (``None`` für Notifications)."""
    method = msg.get("method")
    log.info("MCP-RPC %s", method)
    if "id" not in msg:
        return None
    rid = msg["id"]
    params = msg.get("params") or {}
    if method == "initialize":
        result = {
            "protocolVersion": params.get("protocolVersion") or PROTOCOL,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": {"name": "captain-mcp-test", "version": "1.0.0"},
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        result = _call(params.get("name", ""), params.get("arguments") or {})
    else:
        return {"jsonrpc": "2.0", "id": rid,
                "error": {"code": -32601, "message": f"Methode nicht unterstützt: {method}"}}
    return {"jsonrpc": "2.0", "id": rid, "result": result}


class Handler(BaseHTTPRequestHandler):
    token: str | None = None

    def log_message(self, fmt, *args):  # noqa: A003
        log.info("%s %s", self.address_string(), fmt % args)

    def _authorized(self) -> bool:
        if not self.token:
            return True
        return self.headers.get("Authorization", "") == f"Bearer {self.token}"

    def _send(self, status: int, body: object | None = None) -> None:
        data = b"" if body is None else json.dumps(body).encode()
        self.send_response(status)
        if body is not None:
            self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):  # noqa: N802
        if not self._authorized():
            return self._send(401, {"error": "unauthorized"})
        length = int(self.headers.get("Content-Length") or 0)
        try:
            payload = json.loads(self.rfile.read(length) or b"null")
        except json.JSONDecodeError:
            return self._send(400, {"jsonrpc": "2.0", "id": None,
                                    "error": {"code": -32700, "message": "Parse error"}})
        if isinstance(payload, list):
            out = [r for r in (handle(m) for m in payload) if r is not None]
            return self._send(200, out) if out else self._send(202)
        out = handle(payload)
        return self._send(200, out) if out is not None else self._send(202)

    def do_GET(self):  # noqa: N802
        if self.path.rstrip("/") in ("", "/health"):
            return self._send(200, {"status": "ok"})
        self._send(405)

    def do_DELETE(self):  # noqa: N802
        self._send(405)


def serve(port: int = 0, host: str = "0.0.0.0", token: str | None = None) -> ThreadingHTTPServer:
    """Startet den Server in einem Daemon-Thread; ``server.server_port`` ist der Port."""
    handler = type("TokenHandler", (Handler,), {"token": token})
    server = ThreadingHTTPServer((host, port), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    port = int(os.environ.get("MCP_PORT", "8000"))
    token = os.environ.get("MCP_TOKEN") or os.environ.get("MCP_TEST_TOKEN") or None
    handler = type("TokenHandler", (Handler,), {"token": token})
    log.warning("MCP-Testserver auf Port %d (Token %s)", port, "an" if handler.token else "aus")
    ThreadingHTTPServer(("0.0.0.0", port), handler).serve_forever()
