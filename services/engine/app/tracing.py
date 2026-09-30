"""
X-Trace-Id propagation (root CLAUDE.md).

The trace_id is generated once at the Spring Boot Core gateway and arrives
here as the X-Trace-Id header. This service reuses it — in logs, in error
envelopes, and echoed back on the response — and never replaces it.
"""

import logging
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar

from starlette.datastructures import Headers, MutableHeaders
from starlette.types import ASGIApp, Message, Receive, Scope, Send

TRACE_ID_HEADER = "X-Trace-Id"
_MAX_TRACE_ID_LENGTH = 128

_trace_id_var: ContextVar[str] = ContextVar("trace_id", default="-")
logger = logging.getLogger(__name__)


def current_trace_id() -> str:
    return _trace_id_var.get()


@contextmanager
def bind_trace_id(trace_id: str) -> Iterator[None]:
    """
    Makes `trace_id` the current one for log records inside the block. Needed
    by code that runs outside TraceIdMiddleware (Starlette's catch-all 500
    handler does), where the middleware's own binding has already been reset.
    """
    token = _trace_id_var.set(trace_id)
    try:
        yield
    finally:
        _trace_id_var.reset(token)


class TraceIdLogFilter(logging.Filter):
    """Adds the current request's trace_id to every log record."""

    def filter(self, record: logging.LogRecord) -> bool:
        record.trace_id = _trace_id_var.get()
        return True


def configure_logging(level: str) -> None:
    root = logging.getLogger()
    root.setLevel(level.upper())
    logging.getLogger("httpx").setLevel(logging.WARNING)  # one INFO line per HTTP call is noise
    if any(getattr(h, "_trace_engine", False) for h in root.handlers):
        return
    handler = logging.StreamHandler()
    handler._trace_engine = True  # type: ignore[attr-defined]
    handler.addFilter(TraceIdLogFilter())
    handler.setFormatter(
        logging.Formatter("%(asctime)s %(levelname)s [%(trace_id)s] %(name)s: %(message)s")
    )
    root.addHandler(handler)


class TraceIdMiddleware:
    """
    Pure ASGI middleware (not BaseHTTPMiddleware, which breaks contextvars for
    background work). Puts the trace_id on the ASGI scope state — where error
    handlers running outside this middleware can still read it — and in a
    contextvar for logging.
    """

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        trace_id = Headers(scope=scope).get(TRACE_ID_HEADER)
        if not trace_id or len(trace_id) > _MAX_TRACE_ID_LENGTH:
            trace_id = str(uuid.uuid4())
            # Internal calls must carry the gateway's id; a missing one is a
            # caller bug worth surfacing. /health is polled without one.
            if scope["path"].startswith("/internal/"):
                logger.warning(
                    "%s missing or invalid on %s; using a locally generated fallback id",
                    TRACE_ID_HEADER,
                    scope["path"],
                )

        scope.setdefault("state", {})["trace_id"] = trace_id

        async def send_with_trace_id(message: Message) -> None:
            if message["type"] == "http.response.start":
                MutableHeaders(scope=message)[TRACE_ID_HEADER] = trace_id
            await send(message)

        with bind_trace_id(trace_id):
            await self.app(scope, receive, send_with_trace_id)
