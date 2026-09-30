"""
Best-effort Redis cache (TDD §4.2, cache-aside). The cache is an optimisation,
never a dependency: every failure — Redis down, slow, or holding something we
can't parse — is logged and treated as a miss, so a request never fails
because of the cache.
"""

import json
import logging
from typing import Protocol

from redis.asyncio import Redis
from redis.exceptions import RedisError

logger = logging.getLogger(__name__)

# Fail fast: a cache that answers slowly is worse than no cache.
REDIS_TIMEOUT_SECONDS = 0.2


class Cache(Protocol):
    async def get_json(self, key: str) -> dict | None: ...

    async def set_json(self, key: str, value: dict, ttl_seconds: int) -> None: ...


def create_redis(host: str, port: int) -> Redis:
    return Redis(
        host=host,
        port=port,
        decode_responses=True,
        socket_timeout=REDIS_TIMEOUT_SECONDS,
        socket_connect_timeout=REDIS_TIMEOUT_SECONDS,
    )


class RedisCache:
    def __init__(self, redis: Redis) -> None:
        self._redis = redis

    async def get_json(self, key: str) -> dict | None:
        try:
            raw = await self._redis.get(key)
        except (RedisError, OSError) as exc:
            logger.warning(
                "Cache read failed for %s (%s); treating as miss", key, type(exc).__name__
            )
            return None
        if raw is None:
            return None
        try:
            value = json.loads(raw)
        except ValueError:
            value = None
        if not isinstance(value, dict):
            # Not our format (e.g. written by another service) — never crash on it.
            logger.warning("Cache entry %s is not a JSON object; ignoring it", key)
            return None
        return value

    async def set_json(self, key: str, value: dict, ttl_seconds: int) -> None:
        try:
            await self._redis.set(key, json.dumps(value), ex=ttl_seconds)
        except (RedisError, OSError) as exc:
            logger.warning("Cache write failed for %s (%s); continuing", key, type(exc).__name__)
