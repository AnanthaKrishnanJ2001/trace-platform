import json
from decimal import Decimal

from app.tools.records import PurchaseOrderRecord
from app.tools.reference_data import PO_TTL_SECONDS, VENDOR_TTL_SECONDS, ReferenceDataTools
from tests.fakes import INV_10492, PO_7821, VENDOR_ABC, FakeCache, FakeStore, demo_store


def tools(cache: FakeCache | None = None, store=None):
    store = store or demo_store()
    cache = cache if cache is not None else FakeCache()
    return ReferenceDataTools(store, cache), store, cache


async def test_ttls_match_tdd_section_4_2():
    assert PO_TTL_SECONDS == 30 * 60
    assert VENDOR_TTL_SECONDS == 60 * 60


async def test_po_miss_reads_database_and_populates_cache():
    t, store, cache = tools()
    assert await t.get_purchase_order("PO-7821") == PO_7821
    assert store.calls == [("get_purchase_order", "PO-7821")]
    assert cache.ttls == {"po:PO-7821": PO_TTL_SECONDS}
    json.dumps(cache.entries["po:PO-7821"])  # what we cache must be plain JSON


async def test_vendor_miss_reads_database_and_populates_cache():
    t, store, cache = tools()
    assert await t.get_vendor("VEND-3391") == VENDOR_ABC
    assert cache.ttls == {"vendor:VEND-3391": VENDOR_TTL_SECONDS}


async def test_hit_is_served_from_cache_without_touching_the_database():
    cache = FakeCache({"vendor:VEND-3391": VENDOR_ABC.model_dump(mode="json")})
    t, store, _ = tools(cache)
    assert await t.get_vendor("VEND-3391") == VENDOR_ABC
    assert store.calls == []
    assert cache.sets == []


async def test_invoices_are_never_cached():
    t, store, cache = tools()
    assert await t.get_invoice("INV-10492") == INV_10492
    await t.get_invoice("INV-10492")
    assert store.calls == [("get_invoice", "INV-10492")] * 2
    assert cache.gets == [] and cache.sets == []


async def test_not_found_is_returned_and_not_cached():
    t, _, cache = tools()
    assert await t.get_purchase_order("PO-0000") is None
    assert cache.sets == []


async def test_unrecognised_cache_entry_falls_through_and_is_left_alone():
    foreign = {"some": "other service's format"}
    cache = FakeCache({"po:PO-7821": foreign})
    t, store, _ = tools(cache)
    assert await t.get_purchase_order("PO-7821") == PO_7821
    assert store.calls == [("get_purchase_order", "PO-7821")]
    assert cache.entries["po:PO-7821"] == foreign  # not overwritten
    assert cache.sets == []


async def test_amounts_survive_the_cache_round_trip_exactly():
    # Includes the largest value NUMERIC(14,2) allows.
    for amount in ("45000.10", "999999999999.99", "0.01"):
        po = PurchaseOrderRecord(
            po_id="PO-9999",
            vendor_id="VEND-3391",
            approved_amount=Decimal(amount),
            currency="INR",
            status="OPEN",
        )
        cache = FakeCache()
        t = ReferenceDataTools(FakeStore(purchase_orders=[po]), cache)
        await t.get_purchase_order("PO-9999")  # miss: writes the cache
        # Pass through real JSON text, as Redis would hold it.
        cache.entries["po:PO-9999"] = json.loads(json.dumps(cache.entries["po:PO-9999"]))
        again = await t.get_purchase_order("PO-9999")  # hit: reads it back
        assert again.approved_amount == Decimal(amount)
