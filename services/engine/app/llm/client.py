"""
The engine's only view of a language model. Agents depend on this protocol,
never on the Gemini SDK, so timeouts/retries live in one place and tests can
substitute a fake (tests never call the real API).
"""

from typing import Literal, Protocol, TypeVar

from pydantic import BaseModel

from app.deadline import Deadline

T = TypeVar("T", bound=BaseModel)
EmbedTask = Literal["query", "document"]


class LlmError(Exception):
    """Base for every model-call failure."""


class LlmUnavailable(LlmError):
    """Transient: overloaded, rate limited, timed out, or out of time budget. Degrade, don't fail."""


class LlmRequestError(LlmError):
    """Not transient (bad request, bad key, unexpected response shape): retrying won't help."""


class LlmClient(Protocol):
    async def generate_structured(self, prompt: str, schema: type[T], deadline: Deadline) -> T:
        """Returns the model's answer parsed into `schema`."""
        ...

    async def embed(
        self, texts: list[str], *, task: EmbedTask, deadline: Deadline
    ) -> list[list[float]]:
        """One vector per text, each with the configured number of dimensions."""
        ...
