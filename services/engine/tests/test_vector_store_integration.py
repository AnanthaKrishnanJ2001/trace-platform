"""
pgvector queries against a real Postgres (see test_integration.py for the env vars).
Vectors are synthetic and axis-aligned so similarities can be computed by hand;
every row the tests create is removed afterwards.
"""

import math
import os

import pytest
from sqlalchemy import text

from app.db import create_db_engine
from app.tools.vector_store import PolicyChunkToStore, PostgresVectorStore, to_vector_literal

pytestmark = pytest.mark.integration

DATABASE_URL = os.environ.get("TEST_DATABASE_URL")
DIMS = 768
DOC = "Test Policy Document"
OTHER_DOC = "Other Test Document"
INCIDENTS = ["INC-T0001", "INC-T0002"]


def axis(*weights: float) -> list[float]:
    """A 768-dim vector with the given leading components (rest zero)."""
    return list(weights) + [0.0] * (DIMS - len(weights))


@pytest.fixture
async def engine():
    if not DATABASE_URL:
        pytest.skip("TEST_DATABASE_URL not set")
    engine = create_db_engine(DATABASE_URL)
    await _cleanup(engine)
    yield engine
    await _cleanup(engine)
    await engine.dispose()


async def _cleanup(engine):
    async with engine.begin() as conn:
        await conn.execute(
            text("DELETE FROM policy_chunks WHERE document_name IN (:a, :b)"),
            {"a": DOC, "b": OTHER_DOC},
        )
        await conn.execute(
            text("DELETE FROM incident_embeddings WHERE incident_id = ANY(:ids)"),
            {"ids": INCIDENTS},
        )
        await conn.execute(
            text("DELETE FROM incidents WHERE incident_id = ANY(:ids)"), {"ids": INCIDENTS}
        )


async def _insert_incidents(engine):
    async with engine.begin() as conn:
        for incident_id, resolution, outcome in (
            ("INC-T0001", "Vendor issued corrected invoice.", "RESOLVED"),
            ("INC-T0002", "Change order approved.", "RESOLVED"),
        ):
            await conn.execute(
                text(
                    "INSERT INTO incidents (incident_id, root_cause, resolution, decision, evidence,"
                    " outcome, resolved_at) VALUES (:i, 'PO_INVOICE_AMOUNT_MISMATCH', :r, 'Held',"
                    " '[]'::jsonb, :o, now())"
                ),
                {"i": incident_id, "r": resolution, "o": outcome},
            )


def test_vector_literal_format():
    assert to_vector_literal([0.5, 1, -2.25]) == "[0.5,1.0,-2.25]"


async def test_policy_search_ranks_by_cosine_similarity(engine):
    store = PostgresVectorStore(engine)
    await store.replace_policy_document(
        DOC,
        [
            PolicyChunkToStore("T-1", "on the x axis", axis(1)),
            PolicyChunkToStore("T-2", "between x and y", axis(1, 1)),
            PolicyChunkToStore("T-3", "on the y axis", axis(0, 1)),
        ],
    )
    # Wide top_k + filter to our document, so any real policy rows can't interfere.
    results = await store.search_policy(axis(1), top_k=50)
    mine = [r for r in results if r.document_name == DOC]
    assert [r.clause_id for r in mine] == ["T-1", "T-2", "T-3"]  # exact ranking order
    by_id = {r.clause_id: r for r in mine}
    assert by_id["T-1"].similarity == pytest.approx(1.0)
    assert by_id["T-1"].text == "on the x axis"
    assert by_id["T-3"].similarity == pytest.approx(0.0, abs=1e-6)
    top = await store.search_policy(axis(1), top_k=1)
    assert top[0].similarity == pytest.approx(1.0)
    diag = await store.search_policy(axis(1, 1), top_k=1)
    assert diag[0].clause_id == "T-2" and diag[0].similarity == pytest.approx(1.0)
    # cos(45 degrees) between the x axis and the diagonal
    all_rows = await store.search_policy(axis(1), top_k=50)
    t2 = next(r for r in all_rows if r.clause_id == "T-2")
    assert t2.similarity == pytest.approx(math.cos(math.pi / 4))


async def test_replacing_a_document_is_idempotent_and_leaves_other_documents_alone(engine):
    store = PostgresVectorStore(engine)
    await store.replace_policy_document(
        OTHER_DOC, [PolicyChunkToStore("O-1", "other", axis(0, 0, 1))]
    )
    chunks = [
        PolicyChunkToStore("T-1", "one", axis(1)),
        PolicyChunkToStore("T-2", "two", axis(0, 1)),
    ]
    assert await store.replace_policy_document(DOC, chunks) == 2
    assert await store.replace_policy_document(DOC, chunks[:1]) == 1  # re-run with fewer clauses

    async with engine.connect() as conn:
        counts = dict(
            (
                await conn.execute(
                    text(
                        "SELECT document_name, count(*) FROM policy_chunks "
                        "WHERE document_name IN (:a, :b) GROUP BY 1"
                    ),
                    {"a": DOC, "b": OTHER_DOC},
                )
            ).all()
        )
    assert counts == {DOC: 1, OTHER_DOC: 1}


async def test_incident_search_joins_the_incident_and_upsert_does_not_duplicate(engine):
    await _insert_incidents(engine)
    store = PostgresVectorStore(engine)

    pending = {r["incident_id"] for r in await store.incidents_without_embeddings()}
    assert set(INCIDENTS) <= pending

    await store.upsert_incident_embedding("INC-T0001", "first text", axis(1))
    await store.upsert_incident_embedding(
        "INC-T0001", "revised text", axis(1)
    )  # same incident again
    await store.upsert_incident_embedding("INC-T0002", "second text", axis(0, 1))

    pending_after = {r["incident_id"] for r in await store.incidents_without_embeddings()}
    assert not set(INCIDENTS) & pending_after

    matches = await store.search_incidents(axis(1), top_k=50)
    mine = {m.incident_id: m for m in matches if m.incident_id in INCIDENTS}
    assert set(mine) == set(INCIDENTS)
    first = mine["INC-T0001"]
    assert first.summary_text == "revised text"
    assert first.similarity == pytest.approx(1.0)
    assert (first.resolution, first.outcome, first.root_cause) == (
        "Vendor issued corrected invoice.",
        "RESOLVED",
        "PO_INVOICE_AMOUNT_MISMATCH",
    )
    assert mine["INC-T0002"].similarity == pytest.approx(0.0, abs=1e-6)
    async with engine.connect() as conn:
        n = (
            await conn.execute(
                text("SELECT count(*) FROM incident_embeddings WHERE incident_id='INC-T0001'")
            )
        ).scalar()
    assert n == 1
