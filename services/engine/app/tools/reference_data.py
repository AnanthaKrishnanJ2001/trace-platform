"""
The Investigation Agent's data tools: get_invoice, get_purchase_order,
get_vendor. Purchase orders and vendors are read through Redis
(cache-aside, TDD §4.2); invoices aren't in the TDD's cache table, so they
always come from PostgreSQL.

Cache keys and TTLs are TDD §4.2's exactly. Spring Boot Core is expected to
DEL a key when it updates the underlying row, so don't rename them.
"""

import logging
from collections.abc import Awaitable, Callable
from typing import TypeVar

from pydantic import BaseModel, ValidationError

from app.cache import Cache
from app.tools.records import InvoiceRecord, PurchaseOrderRecord, VendorRecord
from app.tools.store import ReferenceDataStore

logger = logging.getLogger(__name__)

VENDOR_TTL_SECONDS = 60 * 60
PO_TTL_SECONDS = 30 * 60

R = TypeVar("R", bound=BaseModel)


class ReferenceDataTools:
    def __init__(self, store: ReferenceDataStore, cache: Cache) -> None:
        self._store = store
        self._cache = cache

    async def get_invoice(self, invoice_id: str) -> InvoiceRecord | None:
        return await self._store.get_invoice(invoice_id)

    async def get_purchase_order(self, po_id: str) -> PurchaseOrderRecord | None:
        return await self._cached(
            f"po:{po_id}",
            PO_TTL_SECONDS,
            PurchaseOrderRecord,
            self._store.get_purchase_order,
            po_id,
        )

    async def get_vendor(self, vendor_id: str) -> VendorRecord | None:
        return await self._cached(
            f"vendor:{vendor_id}",
            VENDOR_TTL_SECONDS,
            VendorRecord,
            self._store.get_vendor,
            vendor_id,
        )

    async def _cached(
        self,
        key: str,
        ttl_seconds: int,
        model: type[R],
        load: Callable[[str], Awaitable[R | None]],
        entity_id: str,
    ) -> R | None:
        cached = await self._cache.get_json(key)
        if cached is not None:
            try:
                return model.model_validate(cached)
            except ValidationError:
                # Someone else's format under our key. Read through, but leave
                # the entry alone rather than clobber what may be another
                # service's data.
                logger.warning(
                    "Cache entry %s has an unexpected shape; reading from the database", key
                )
                return await load(entity_id)

        record = await load(entity_id)
        if record is not None:  # misses aren't cached: a row created a moment later must be seen
            await self._cache.set_json(key, record.model_dump(mode="json"), ttl_seconds)
        return record
