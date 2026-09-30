"""
Gemini implementation of LlmClient (Google Gen AI SDK, API-key auth).

Retry policy is shaped by the 8s investigation budget rather than TDD §5.4's
"5s x 2 retries", which cannot fit in it: every attempt gets
min(per-call timeout, time left), and a transient failure moves to the
fallback model instead of retrying the same overloaded one. Prompts and
responses are never logged.
"""

import asyncio
import logging

import httpx
from google.genai import Client, types
from google.genai.errors import APIError

from app.config import Settings
from app.deadline import Deadline
from app.llm.client import EmbedTask, LlmRequestError, LlmUnavailable, T

logger = logging.getLogger(__name__)

# Worth trying another model (or trying again) for: overload, rate limits,
# server errors, and a model that has been retired for new users (404).
_TRANSIENT_CODES = {429, 500, 502, 503, 504}
_MODEL_GONE_CODES = {404}
# Below this there is no point starting a call.
_MIN_BUDGET_SECONDS = 0.4
_EMBED_ATTEMPTS = 2
# The SDK lets low-level network failures (e.g. "server disconnected without
# sending a response") escape as raw httpx errors rather than APIError.
_TRANSPORT_ERRORS = (httpx.HTTPError, ConnectionError)


class GeminiClient:
    def __init__(
        self,
        client: Client,
        *,
        model: str,
        fallback_model: str | None,
        thinking_level: str | None,
        embedding_model: str,
        embedding_dimensions: int,
        call_timeout_seconds: float,
    ) -> None:
        self._client = client
        self._models = [model] + ([fallback_model] if fallback_model else [])
        self._thinking_level = thinking_level
        self._embedding_model = embedding_model
        self._dimensions = embedding_dimensions
        self._call_timeout = call_timeout_seconds

    @classmethod
    def from_settings(
        cls, settings: Settings, *, call_timeout_seconds: float | None = None
    ) -> "GeminiClient":
        return cls(
            Client(api_key=settings.gemini_api_key.get_secret_value()),
            model=settings.gemini_model,
            fallback_model=settings.gemini_fallback_model,
            thinking_level=settings.gemini_thinking_level,
            embedding_model=settings.embedding_model,
            embedding_dimensions=settings.embedding_dimensions,
            call_timeout_seconds=(
                settings.llm_call_timeout_seconds
                if call_timeout_seconds is None
                else call_timeout_seconds
            ),
        )

    async def generate_structured(self, prompt: str, schema: type[T], deadline: Deadline) -> T:
        config = types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=schema,
            automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
            thinking_config=(
                types.ThinkingConfig(thinking_level=self._thinking_level)
                if self._thinking_level
                else None
            ),
        )
        last_reason = "no time left to call the model"
        for model in self._models:
            budget = min(self._call_timeout, deadline.remaining())
            if budget < _MIN_BUDGET_SECONDS:
                break
            try:
                response = await asyncio.wait_for(
                    self._client.aio.models.generate_content(
                        model=model, contents=prompt, config=config
                    ),
                    timeout=budget,
                )
            except TimeoutError:
                last_reason = f"{model} timed out after {budget:.1f}s"
            except _TRANSPORT_ERRORS as exc:
                last_reason = f"{model} network error ({type(exc).__name__})"
            except APIError as exc:
                if exc.code in _TRANSIENT_CODES | _MODEL_GONE_CODES:
                    last_reason = f"{model} returned {exc.code}"
                else:
                    raise LlmRequestError(f"{model} rejected the request ({exc.code})") from exc
            else:
                parsed = response.parsed
                if isinstance(parsed, schema):
                    return parsed
                last_reason = f"{model} returned output that did not match the schema"
            logger.warning("Gemini generate failed: %s", last_reason)
        raise LlmUnavailable(last_reason)

    async def embed(
        self, texts: list[str], *, task: EmbedTask, deadline: Deadline
    ) -> list[list[float]]:
        config = types.EmbedContentConfig(
            output_dimensionality=self._dimensions,
            task_type="RETRIEVAL_QUERY" if task == "query" else "RETRIEVAL_DOCUMENT",
        )
        # One Content per text. Passing a plain list of strings makes
        # gemini-embedding-2 merge them into a single aggregated vector
        # (verified against the live API), silently corrupting batches.
        contents = [types.Content(parts=[types.Part(text=t)]) for t in texts]
        last_reason = "no time left to call the model"
        for _ in range(_EMBED_ATTEMPTS):
            budget = min(self._call_timeout, deadline.remaining())
            if budget < _MIN_BUDGET_SECONDS:
                break
            try:
                response = await asyncio.wait_for(
                    self._client.aio.models.embed_content(
                        model=self._embedding_model, contents=contents, config=config
                    ),
                    timeout=budget,
                )
            except TimeoutError:
                last_reason = f"embedding timed out after {budget:.1f}s"
            except _TRANSPORT_ERRORS as exc:
                last_reason = f"embedding network error ({type(exc).__name__})"
            except APIError as exc:
                if exc.code not in _TRANSIENT_CODES:
                    raise LlmRequestError(f"embedding request rejected ({exc.code})") from exc
                last_reason = f"embedding returned {exc.code}"
            else:
                return self._validated(response, len(texts))
            logger.warning("Gemini embed failed: %s", last_reason)
        raise LlmUnavailable(last_reason)

    def _validated(self, response, expected_count: int) -> list[list[float]]:
        vectors = [list(e.values) for e in (response.embeddings or [])]
        if len(vectors) != expected_count or any(len(v) != self._dimensions for v in vectors):
            # The database columns are fixed-width; storing or comparing a
            # different size would fail confusingly later, so fail here.
            raise LlmRequestError(
                f"embedding response has an unexpected shape (wanted {expected_count} x "
                f"{self._dimensions})"
            )
        return vectors
