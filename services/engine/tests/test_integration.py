"""
Runs the tools and Investigation Agent against a real Postgres (migrations +
demo seed applied) and a real Redis. Skipped unless both are configured:

    TEST_DATABASE_URL=postgresql+psycopg://user:pass@host:port/db
    TEST_REDIS_URL=redis://host:port/0
"""

import json
import os
import time

import pytest
from redis.asyncio import Redis
from sqlalchemy import text
from sqlalchemy.exc import OperationalError

from app.agents.investigation import InvestigationAgent
from app.agents.trace import TraceRecorder
from app.api.schemas import References
from app.cache import RedisCache, create_redis
from app.db import QUERY_TIMEOUT_MS, create_db_engine
from app.tools.reference_data import PO_TTL_SECONDS, VENDOR_TTL_SECONDS, ReferenceDataTools
from app.tools.store import PostgresReferenceStore
from tests.fakes import INV_10492, PO_7821, VENDOR_ABC

pytestmark = pytest.mark.integration

DATABASE_URL = os.environ.get("TEST_DATABASE_URL")
REDIS_URL = os.environ.get("TEST_REDIS_URL")
# The only keys these tests touch — never flush a Redis that might be shared.
CACHE_KEYS = ["po:PO-7821", "vendor:VEND-3391"]
DEMO_REFS = References(invoice_id="INV-10492", po_id="PO-7821", vendor_id="VEND-3391")


class CountingStore(PostgresReferenceStore):
    def __init__(self, engine) -> None:
        super().__init__(engine)
        self.calls: list[str] = []

    async def get_invoice(self, invoice_id):
        self.calls.append("get_invoice")
        return await super().get_invoice(invoice_id)

    async def get_purchase_order(self, po_id):
        self.calls.append("get_purchase_order")
        return await super().get_purchase_order(po_id)

    async def get_vendor(self, vendor_id):
        self.calls.append("get_vendor")
        return await super().get_vendor(vendor_id)


@pytest.fixture
async def engine():
    if not DATABASE_URL:
        pytest.skip("TEST_DATABASE_URL not set")
    engine = create_db_engine(DATABASE_URL)
    yield engine
    await engine.dispose()


@pytest.fixture
async def redis_client():
    if not REDIS_URL:
        pytest.skip("TEST_REDIS_URL not set")
    client = Redis.from_url(REDIS_URL, decode_responses=True)
    await client.delete(*CACHE_KEYS)
    yield client
    await client.delete(*CACHE_KEYS)
    await client.aclose()


async def test_store_reads_the_seeded_demo_records(engine):
    store = PostgresReferenceStore(engine)
    assert await store.get_invoice("INV-10492") == INV_10492
    assert await store.get_purchase_order("PO-7821") == PO_7821
    assert await store.get_vendor("VEND-3391") == VENDOR_ABC


async def test_store_returns_none_for_unknown_ids(engine):
    store = PostgresReferenceStore(engine)
    assert await store.get_invoice("INV-00000") is None
    assert await store.get_purchase_order("PO-00000") is None
    assert await store.get_vendor("VEND-00000") is None


async def test_slow_queries_are_cancelled_by_the_server_side_timeout(engine):
    started = time.monotonic()
    async with engine.connect() as conn:
        with pytest.raises(OperationalError):
            await conn.execute(text("SELECT pg_sleep(10)"))
    elapsed = time.monotonic() - started
    assert QUERY_TIMEOUT_MS / 1000 <= elapsed < 5


async def test_investigation_over_real_postgres_and_redis(engine, redis_client):
    store = CountingStore(engine)
    cache = RedisCache(redis_client)
    agent = InvestigationAgent(ReferenceDataTools(store, cache))

    first = await agent.run(DEMO_REFS, 2.0, TraceRecorder())
    assert first.findings.status == "OK"
    assert (first.findings.invoice_amount, first.findings.po_amount) == (
        PO_7821.approved_amount + 7000,
        PO_7821.approved_amount,
    )
    assert first.analysis.within_tolerance is False

    # TDD §4.2 keys, with their TTLs, now hold the PO and vendor.
    assert 0 < await redis_client.ttl("po:PO-7821") <= PO_TTL_SECONDS
    assert 0 < await redis_client.ttl("vendor:VEND-3391") <= VENDOR_TTL_SECONDS
    assert json.loads(await redis_client.get("po:PO-7821"))["approved_amount"] == 45000.0
    assert await redis_client.exists("invoice:INV-10492") == 0  # invoices aren't cached

    second = await agent.run(DEMO_REFS, 2.0, TraceRecorder())
    assert second.findings == first.findings
    # PO and vendor came from Redis the second time; the invoice always hits Postgres.
    assert sorted(store.calls) == sorted(
        ["get_invoice", "get_purchase_order", "get_vendor", "get_invoice"]
    )


async def test_investigation_still_works_when_redis_is_down(engine):
    dead_cache = RedisCache(create_redis("127.0.0.1", 1))  # nothing listens on port 1
    agent = InvestigationAgent(ReferenceDataTools(PostgresReferenceStore(engine), dead_cache))

    outcome = await agent.run(DEMO_REFS, 2.0, TraceRecorder())
    assert outcome.findings.status == "OK"
    assert outcome.findings.difference == 7000
