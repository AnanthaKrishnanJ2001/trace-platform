# Invoice Validation Policy

> Illustrative policy text for the TRACE demo. Clause IVP-4.2 is the wording used
> in the technical design document; the other clauses are representative
> accounts-payable rules written so retrieval has realistic neighbours to rank.
> Replace with the organisation's real policy before any production use.

## IVP-1.1 Required invoice data
Every invoice must carry the vendor identifier, an invoice number, an invoice date, the purchase order reference and the invoiced amount before it can be processed for payment.

## IVP-2.1 Vendor of record
The vendor named on an invoice must match the vendor of record on the referenced purchase order. An invoice from a different vendor must be held and escalated.

## IVP-3.1 Duplicate invoices
An invoice with the same vendor, invoice number and amount as one already received must be blocked as a suspected duplicate and must not be paid.

## IVP-4.1 Three-way match
Payment requires the invoice, the purchase order and the goods or services receipt to agree on quantity, price and total before release.

## IVP-4.2 Invoice amount versus purchase order
Invoice amount cannot exceed PO amount without an approved change order.

## IVP-4.3 Tolerance for minor variances
A variance between the invoice amount and the PO amount that is within the configured tolerance (2% of the PO amount unless a different tolerance is set) may be approved by the accounts payable analyst without a change order.

## IVP-5.1 Payment hold on exceptions
An invoice that fails validation must be held from payment until the exception is resolved and the resolution is recorded in the audit log.

## IVP-5.2 Corrective action
When an invoice exceeds the purchase order amount beyond tolerance, the analyst must request a corrected invoice from the vendor or obtain an approved change order. Payment stays on hold until one is received.
