import logging

import pytest

from app.db import MAX_ATTEMPTS, DataAccessError, create_db_engine
from app.tools.store import PostgresReferenceStore


async def test_unreachable_database_is_retried_once_then_raises_data_access_error(caplog):
    engine = create_db_engine("postgresql+psycopg://u:p@127.0.0.1:1/db")  # nothing listens on 1
    try:
        with caplog.at_level(logging.WARNING, logger="app.db"):
            with pytest.raises(DataAccessError):
                await PostgresReferenceStore(engine).get_vendor("VEND-3391")
    finally:
        await engine.dispose()

    assert MAX_ATTEMPTS == 2
    attempts = [r.getMessage() for r in caplog.records if "attempt" in r.getMessage()]
    assert len(attempts) == MAX_ATTEMPTS
    assert "attempt 1/2" in attempts[0] and "attempt 2/2" in attempts[1]


async def test_data_access_error_does_not_leak_connection_details(caplog):
    engine = create_db_engine("postgresql+psycopg://someuser:hunter2@127.0.0.1:1/db")
    try:
        with caplog.at_level(logging.DEBUG):
            with pytest.raises(DataAccessError) as excinfo:
                await PostgresReferenceStore(engine).get_invoice("INV-10492")
    finally:
        await engine.dispose()

    assert "hunter2" not in str(excinfo.value)
    assert "hunter2" not in caplog.text
