"""
Prints a short-lived internal JWT for calling /internal/v1/* by hand in local
development (Spring Boot Core doesn't issue these yet). Signs with the same
INTERNAL_JWT_SECRET the service verifies with.

    python -m app.scripts.dev_token
"""

import os
import time

from jose import jwt


def main() -> None:
    secret = os.environ["INTERNAL_JWT_SECRET"]
    audience = os.environ.get("INTERNAL_JWT_AUDIENCE", "fastapi-ai-service")
    claims = {"aud": audience, "sub": "dev-cli", "exp": int(time.time()) + 300}
    print(jwt.encode(claims, secret, algorithm="HS256"))


if __name__ == "__main__":
    main()
