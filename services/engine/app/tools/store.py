"""Read-only PostgreSQL access to the reference tables (vendors, POs, invoices)."""

from collections.abc import Mapping
from typing import Any, Protocol

from sqlalchemy.ext.asyncio import AsyncEngine

from app.db import fetch_rows
from app.tools.records import InvoiceRecord, PurchaseOrderRecord, VendorRecord

_VENDOR_SQL = "SELECT vendor_id, name, category, risk_level FROM vendors WHERE vendor_id = :id"
_PO_SQL = (
    "SELECT po_id, vendor_id, approved_amount, currency, status "
    "FROM purchase_orders WHERE po_id = :id"
)
_INVOICE_SQL = (
    "SELECT invoice_id, vendor_id, po_id, invoice_amount, invoice_date, status "
    "FROM invoices WHERE invoice_id = :id"
)


class ReferenceDataStore(Protocol):
    """Where the tools load records from. Tests substitute an in-memory fake."""

    async def get_invoice(self, invoice_id: str) -> InvoiceRecord | None: ...

    async def get_purchase_order(self, po_id: str) -> PurchaseOrderRecord | None: ...

    async def get_vendor(self, vendor_id: str) -> VendorRecord | None: ...


class PostgresReferenceStore:
    def __init__(self, engine: AsyncEngine) -> None:
        self._engine = engine

    async def get_invoice(self, invoice_id: str) -> InvoiceRecord | None:
        row = await self._fetch_one(_INVOICE_SQL, invoice_id)
        return InvoiceRecord(**row) if row else None

    async def get_purchase_order(self, po_id: str) -> PurchaseOrderRecord | None:
        row = await self._fetch_one(_PO_SQL, po_id)
        return PurchaseOrderRecord(**row) if row else None

    async def get_vendor(self, vendor_id: str) -> VendorRecord | None:
        row = await self._fetch_one(_VENDOR_SQL, vendor_id)
        return VendorRecord(**row) if row else None

    async def _fetch_one(self, sql: str, entity_id: str) -> Mapping[str, Any] | None:
        rows = await fetch_rows(self._engine, sql, {"id": entity_id})
        return rows[0] if rows else None
