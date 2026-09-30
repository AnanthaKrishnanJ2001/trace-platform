from pathlib import Path

import pytest

from app.knowledge.incidents import incident_summary_text
from app.knowledge.policy_parser import parse_policy

POLICY_FILE = (
    Path(__file__).resolve().parents[1] / "app" / "knowledge" / "invoice_validation_policy.md"
)


def test_the_shipped_policy_parses_and_contains_the_tdd_clause():
    doc = parse_policy(POLICY_FILE.read_text(encoding="utf-8"))
    assert doc.name == "Invoice Validation Policy"
    by_id = {c.clause_id: c for c in doc.clauses}
    assert len(doc.clauses) == len(by_id) >= 5
    # The exact wording TDD §3.2 quotes for the demo case.
    assert by_id["IVP-4.2"].text == (
        "Invoice amount cannot exceed PO amount without an approved change order."
    )
    assert all(len(c.clause_id) <= 30 for c in doc.clauses)  # policy_chunks.clause_id VARCHAR(30)


def test_multi_line_bodies_are_joined_and_the_preamble_is_ignored():
    doc = parse_policy(
        "# Doc\n> not a clause\n\nignored preamble\n\n## AB-1.1 First rule\nline one\nline two\n\n"
        "## AB-1.2 Second\nonly line\n"
    )
    assert [c.clause_id for c in doc.clauses] == ["AB-1.1", "AB-1.2"]
    assert doc.clauses[0].text == "line one line two"
    assert doc.clauses[0].embedding_text == "AB-1.1 First rule. line one line two"


@pytest.mark.parametrize(
    ("markdown", "message"),
    [
        ("## AB-1.1 Rule\ntext", "no '# Title'"),
        ("# Doc\njust prose", "no '## <ID>"),
        ("# Doc\n## AB-1.1 Rule\n\n## AB-1.2 Other\ntext", "AB-1.1 has no text"),
        ("# Doc\n## AB-1.1 A\nx\n## AB-1.1 B\ny", "duplicate"),
    ],
    ids=["no-title", "no-clauses", "empty-clause", "duplicate-ids"],
)
def test_malformed_policies_are_rejected(markdown, message):
    with pytest.raises(ValueError, match=message):
        parse_policy(markdown)


def test_incident_summary_leaves_out_evidence_ids_and_handles_missing_decision():
    text = incident_summary_text(
        "PO_INVOICE_AMOUNT_MISMATCH", "Vendor issued corrected invoice.", None
    )
    assert (
        text
        == "Root cause: PO_INVOICE_AMOUNT_MISMATCH. Resolution: Vendor issued corrected invoice."
    )
    with_decision = incident_summary_text("X", "Fixed.", "Payment blocked.")
    assert with_decision.endswith("Decision: Payment blocked.")
