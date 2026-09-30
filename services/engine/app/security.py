"""
Inbound authentication for /internal/v1/* (root CLAUDE.md): every call from
Spring Boot Core carries a signed JWT, audience-restricted to this service.
Neither the token nor the signing secret is ever logged.
"""

import logging

from fastapi import Depends
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from jose import JWTError, jwt

from app.config import Settings, get_settings
from app.errors import ApiError

logger = logging.getLogger(__name__)

_ALGORITHM = "HS256"
_bearer = HTTPBearer(auto_error=False)


def _unauthorized(message: str) -> ApiError:
    return ApiError(401, "UNAUTHORIZED", message, headers={"WWW-Authenticate": "Bearer"})


def verify_internal_jwt(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    settings: Settings = Depends(get_settings),
) -> dict:
    """FastAPI dependency: returns the verified claims or raises a 401 envelope."""
    if credentials is None:
        raise _unauthorized("Missing bearer token.")
    try:
        return jwt.decode(
            credentials.credentials,
            settings.internal_jwt_secret.get_secret_value(),
            # Pinned: never accept the algorithm the token itself claims.
            algorithms=[_ALGORITHM],
            audience=settings.internal_jwt_audience,
            options={"require_exp": True, "require_aud": True},
        )
    except JWTError as exc:
        # Log the failure class only — the message can carry token fragments.
        logger.warning("Rejected internal JWT (%s)", type(exc).__name__)
        raise _unauthorized("Invalid or expired token.") from exc
