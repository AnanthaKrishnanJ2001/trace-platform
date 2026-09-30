"""Typed rows the investigation tools return (columns of TDD §4.1's tables)."""

from datetime import date

from pydantic import BaseModel

from app.types import Money


class VendorRecord(BaseModel):
    vendor_id: str
    name: str
    category: str | None = None
    risk_level: str


class PurchaseOrderRecord(BaseModel):
    po_id: str
    vendor_id: str
    approved_amount: Money
    currency: str
    status: str


class InvoiceRecord(BaseModel):
    invoice_id: str
    vendor_id: str
    po_id: str | None = None
    invoice_amount: Money
    invoice_date: date
    status: str
