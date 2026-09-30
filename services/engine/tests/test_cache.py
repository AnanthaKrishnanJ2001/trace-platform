import json
import time

import pytest
from redis.exceptions import RedisError

from app.cache import RedisCache, create_redis


class FakeRedis:
    def __init__(self, stored=None, error: Exception | None = None) -> None:
        self.stored = dict(stored or {})
        self.error = error
        self.expiries: dict[str, int] = {}

    async def get(self, key):
        if self.error:
            raise self.error
        return self.stored.get(key)

    async def set(self, key, value, ex=None):
        if self.error:
            raise self.error
        self.stored[key] = value
        self.expiries[key] = ex


async def test_miss_returns_none():
    assert await RedisCache(FakeRedis()).get_json("k") is None


async def test_round_trip_stores_json_with_the_ttl():
    redis = FakeRedis()
    cache = RedisCache(redis)
    await cache.set_json("vendor:V", {"a": 1}, 3600)
    assert json.loads(redis.stored["vendor:V"]) == {"a": 1}
    assert redis.expiries["vendor:V"] == 3600
    assert await cache.get_json("vendor:V") == {"a": 1}


@pytest.mark.parametrize(
    "raw",
    ["{not json", "[1, 2]", '"a string"', "42", ""],
    ids=["garbage", "list", "str", "int", "empty"],
)
async def test_entries_that_are_not_json_objects_are_treated_as_a_miss(raw):
    assert await RedisCache(FakeRedis({"k": raw})).get_json("k") is None


async def test_redis_errors_are_swallowed():
    cache = RedisCache(FakeRedis(error=RedisError("boom")))
    assert await cache.get_json("k") is None
    await cache.set_json("k", {"a": 1}, 60)  # must not raise


async def test_unreachable_redis_degrades_quickly_instead_of_failing():
    cache = RedisCache(create_redis("127.0.0.1", 1))  # nothing listens on port 1
    started = time.monotonic()
    assert await cache.get_json("k") is None
    await cache.set_json("k", {"a": 1}, 60)
    assert time.monotonic() - started < 2
