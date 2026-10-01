"""Deterministisches Fake-LLM mit OpenAI-kompatibler Chat-API (nur für Tests).

opencode spricht es über den generischen Provider (``/v1/chat/completions``,
gestreamt oder nicht). Jede Anfrage wird mit den angebotenen Tool-Namen in
:attr:`FakeLLM.requests` festgehalten – so lässt sich prüfen, welche Tools
opencode dem Modell überhaupt zeigt.

Steuerung über die letzte Nutzernachricht:

- ``TOOL:<name> <json>`` → das Modell ruft ``<name>`` mit ``<json>`` auf
  (auch wenn opencode das Tool gar nicht anbietet),
- folgt auf einen Tool-Aufruf das Ergebnis, antwortet es mit
  ``ERGEBNIS: <Inhalt>``,
- sonst ``OK``.
"""

from __future__ import annotations

import json
import re
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COMMAND = re.compile(r"TOOL:(\S+)(?:\s+(\{.*\}))?", re.DOTALL)


def _text(content) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(p.get("text", "") for p in content if isinstance(p, dict))
    return ""


def decide(body: dict) -> dict:
    """Antwort als ``{"text": …}`` oder ``{"tool": name, "args": {…}}``."""
    messages = body.get("messages") or []
    if not body.get("tools"):  # z. B. Titel-Generierung
        return {"text": "Testtitel"}
    last = messages[-1] if messages else {}
    if last.get("role") == "tool":
        return {"text": f"ERGEBNIS: {_text(last.get('content'))}"}
    user = next((m for m in reversed(messages) if m.get("role") == "user"), {})
    m = COMMAND.search(_text(user.get("content")))
    if m:
        return {"tool": m.group(1), "args": json.loads(m.group(2) or "{}")}
    return {"text": "OK"}


class FakeLLM:
    def __init__(self, host: str = "0.0.0.0", port: int = 0):
        self.requests: list[dict] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):  # noqa: A003
                pass

            def _json(self, status, obj):
                data = json.dumps(obj).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_GET(self):  # noqa: N802
                if self.path.endswith("/models"):
                    return self._json(200, {"object": "list", "data": [
                        {"id": "fake", "object": "model", "created": 0, "owned_by": "test"}]})
                self._json(404, {"error": "not found"})

            def do_POST(self):  # noqa: N802
                length = int(self.headers.get("Content-Length") or 0)
                body = json.loads(self.rfile.read(length) or b"{}")
                tools = [t.get("function", {}).get("name") for t in body.get("tools") or []]
                answer = decide(body)
                outer.requests.append({"tools": tools, "answer": answer,
                                       "auth": self.headers.get("Authorization"), "body": body})
                if body.get("stream"):
                    return self._stream(body, answer)
                self._json(200, _completion(body, answer))

            def _stream(self, body, answer):
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                for chunk in _chunks(body, answer):
                    self.wfile.write(f"data: {json.dumps(chunk)}\n\n".encode())
                    self.wfile.flush()
                self.wfile.write(b"data: [DONE]\n\n")
                self.wfile.flush()

        self.server = ThreadingHTTPServer((host, port), Handler)
        self.port = self.server.server_port
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def _base(body):
    return {"id": f"chatcmpl-{uuid.uuid4().hex[:12]}", "created": int(time.time()),
            "model": body.get("model", "fake")}


def _usage():
    return {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}


def _completion(body, answer):
    if "tool" in answer:
        msg = {"role": "assistant", "content": None, "tool_calls": [{
            "id": f"call_{uuid.uuid4().hex[:8]}", "type": "function",
            "function": {"name": answer["tool"], "arguments": json.dumps(answer["args"])}}]}
        finish = "tool_calls"
    else:
        msg = {"role": "assistant", "content": answer["text"]}
        finish = "stop"
    return {**_base(body), "object": "chat.completion",
            "choices": [{"index": 0, "message": msg, "finish_reason": finish}], "usage": _usage()}


def _chunks(body, answer):
    base = {**_base(body), "object": "chat.completion.chunk"}
    if "tool" in answer:
        yield {**base, "choices": [{"index": 0, "delta": {"role": "assistant", "tool_calls": [{
            "index": 0, "id": f"call_{uuid.uuid4().hex[:8]}", "type": "function",
            "function": {"name": answer["tool"], "arguments": json.dumps(answer["args"])}}]},
            "finish_reason": None}]}
        finish = "tool_calls"
    else:
        yield {**base, "choices": [{"index": 0, "delta": {"role": "assistant", "content": answer["text"]},
                                    "finish_reason": None}]}
        finish = "stop"
    yield {**base, "choices": [{"index": 0, "delta": {}, "finish_reason": finish}], "usage": _usage()}
