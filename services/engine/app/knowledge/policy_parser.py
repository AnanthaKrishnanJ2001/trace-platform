"""
Parses a policy document written as markdown: one `# Title`, then one
`## <CLAUSE-ID> <heading>` section per clause. Each clause becomes one
retrievable chunk.
"""

import re
from dataclasses import dataclass

_TITLE = re.compile(r"^#\s+(?P<name>.+?)\s*$")
_CLAUSE = re.compile(r"^##\s+(?P<id>[A-Z]{2,5}-\d+(?:\.\d+)*)\s+(?P<title>.+?)\s*$")


@dataclass(frozen=True)
class PolicyClauseDoc:
    clause_id: str
    title: str
    text: str

    @property
    def embedding_text(self) -> str:
        # The heading carries meaning ("Tolerance for minor variances") that the
        # body alone may not, so it is embedded with the body; only the body is
        # stored and shown as the clause text.
        return f"{self.clause_id} {self.title}. {self.text}"


@dataclass(frozen=True)
class PolicyDocument:
    name: str
    clauses: list[PolicyClauseDoc]


def parse_policy(markdown: str) -> PolicyDocument:
    name: str | None = None
    clauses: list[PolicyClauseDoc] = []
    current: tuple[str, str] | None = None
    body: list[str] = []

    def close_clause() -> None:
        nonlocal current, body
        if current is None:
            return
        text = " ".join(" ".join(body).split())
        if not text:
            raise ValueError(f"Clause {current[0]} has no text")
        clauses.append(PolicyClauseDoc(clause_id=current[0], title=current[1], text=text))
        current, body = None, []

    for line in markdown.splitlines():
        if (m := _CLAUSE.match(line)) is not None:
            close_clause()
            current = (m["id"], m["title"])
        elif (m := _TITLE.match(line)) is not None and name is None:
            name = m["name"]
        elif current is not None and not line.startswith(">"):
            body.append(line)
    close_clause()

    if name is None:
        raise ValueError("Policy document has no '# Title' heading")
    if not clauses:
        raise ValueError("Policy document has no '## <ID> <heading>' clauses")
    ids = [c.clause_id for c in clauses]
    if len(ids) != len(set(ids)):
        raise ValueError("Policy document has duplicate clause ids")
    return PolicyDocument(name=name, clauses=clauses)
