"""How a resolved incident is turned into the text that gets embedded."""


def incident_summary_text(root_cause: str, resolution: str, decision: str | None) -> str:
    """
    Used for both seeding and memory write-back, so old and new incidents are
    embedded the same way and can be compared. Evidence ids (INV-…, PO-…) are
    left out on purpose: they carry no meaning for semantic similarity and
    would only push similar incidents apart.
    """
    parts = [f"Root cause: {root_cause}.", f"Resolution: {resolution}"]
    if decision:
        parts.append(f"Decision: {decision}")
    return " ".join(parts)
