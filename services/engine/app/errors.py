"""
The standard error envelope (contracts/schemas/error-envelope.schema.json,
TDD §3.4). Every 4xx/5xx this service returns goes through
build_error_response — no raw stack trace or framework default error body
ever reaches the caller.
"""

import logging
import uuid
from datetime import datetime, timezone

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.tracing import TRACE_ID_HEADER, bind_trace_id

logger = logging.getLogger(__name__)

_ERROR_CODES = {
    400: "VALIDATION_FAILED",
    401: "UNAUTHORIZED",
    403: "FORBIDDEN",
    404: "NOT_FOUND",
    405: "METHOD_NOT_ALLOWED",
    409: "CONFLICT",
    422: "UNPROCESSABLE_ENTITY",
    503: "SERVICE_UNAVAILABLE",
    504: "GATEWAY_TIMEOUT",
}
_REQUEST_PARTS = {"body", "query", "path", "header", "cookie"}


class ErrorDetail(BaseModel):
    field: str
    issue: str


class ErrorEnvelope(BaseModel):
    timestamp: datetime
    status: int
    error: str
    message: str
    path: str
    trace_id: str
    details: list[ErrorDetail] | None = None


class ApiError(Exception):
    """Raise from anywhere in the request path to return a specific envelope."""

    def __init__(
        self,
        status_code: int,
        error: str,
        message: str,
        details: list[ErrorDetail] | None = None,
        headers: dict[str, str] | None = None,
    ) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.error = error
        self.message = message
        self.details = details
        self.headers = headers


def _trace_id(request: Request) -> str:
    # Set by TraceIdMiddleware on the ASGI scope, which is shared with handlers
    # that run outside the middleware (the catch-all 500 handler does).
    return getattr(request.state, "trace_id", None) or str(uuid.uuid4())


def build_error_response(
    request: Request,
    status_code: int,
    error: str,
    message: str,
    details: list[ErrorDetail] | None = None,
    headers: dict[str, str] | None = None,
) -> JSONResponse:
    envelope = ErrorEnvelope(
        timestamp=datetime.now(timezone.utc),
        status=status_code,
        error=error,
        message=message,
        path=request.url.path,
        trace_id=_trace_id(request),
        details=details,
    )
    return JSONResponse(
        status_code=status_code,
        content=envelope.model_dump(mode="json", exclude_none=True),
        headers={**(headers or {}), TRACE_ID_HEADER: envelope.trace_id},
    )


def _field_path(loc: tuple) -> str:
    parts = [str(p) for p in loc if p not in _REQUEST_PARTS]
    return ".".join(parts) or "body"


async def _handle_api_error(request: Request, exc: ApiError) -> JSONResponse:
    return build_error_response(
        request, exc.status_code, exc.error, exc.message, exc.details, exc.headers
    )


async def _handle_validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
    errors = exc.errors()
    if any(e["type"] == "json_invalid" for e in errors):
        return build_error_response(
            request, 400, "VALIDATION_FAILED", "Request body is missing or malformed JSON."
        )
    # Only field paths and pydantic's messages go back — never the offending
    # input values, which could echo sensitive data.
    details = [ErrorDetail(field=_field_path(e["loc"]), issue=e["msg"]) for e in errors]
    return build_error_response(
        request, 400, "VALIDATION_FAILED", "One or more fields failed validation.", details
    )


async def _handle_http_exception(request: Request, exc: StarletteHTTPException) -> JSONResponse:
    error = _ERROR_CODES.get(exc.status_code, "HTTP_ERROR")
    message = exc.detail if isinstance(exc.detail, str) else "Request failed."
    return build_error_response(request, exc.status_code, error, message, headers=exc.headers)


async def _handle_unexpected(request: Request, exc: Exception) -> JSONResponse:
    # Runs outside TraceIdMiddleware, so re-bind the id or this log line — the
    # one most worth joining across services — would carry no trace_id.
    with bind_trace_id(_trace_id(request)):
        logger.error("Unhandled exception on %s", request.url.path, exc_info=exc)
    return build_error_response(request, 500, "INTERNAL_ERROR", "An unexpected error occurred.")


def register_exception_handlers(app: FastAPI) -> None:
    app.add_exception_handler(ApiError, _handle_api_error)
    app.add_exception_handler(RequestValidationError, _handle_validation_error)
    app.add_exception_handler(StarletteHTTPException, _handle_http_exception)
    app.add_exception_handler(Exception, _handle_unexpected)
