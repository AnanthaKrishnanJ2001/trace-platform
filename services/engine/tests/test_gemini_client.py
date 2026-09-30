import asyncio
import logging
from types import SimpleNamespace

import httpx
import pytest
from google.genai import types
from google.genai.errors import ClientError, ServerError
from pydantic import BaseModel

from app.deadline import Deadline
from app.llm.client import LlmRequestError, LlmUnavailable
from app.llm.gemini import GeminiClient

PRIMARY, FALLBACK, EMBED = "primary-model", "fallback-model", "embed-model"
DIMS = 4


class Out(BaseModel):
    answer: str


def ok(answer="fine"):
    return SimpleNamespace(parsed=Out(answer=answer))


def err(cls, code):
    return cls(code, {"error": {"message": "boom", "status": "X"}})


class FakeModels:
    """Plays back a scripted outcome per model: a response, an exception, or an async callable."""

    def __init__(self, script):
        self.script = {k: list(v) for k, v in script.items()}
        self.calls: list[str] = []
        self.configs: list = []
        self.contents: list = []

    async def _play(self, model, config, contents=None):
        self.calls.append(model)
        self.configs.append(config)
        self.contents.append(contents)
        outcome = self.script[model].pop(0)
        if callable(outcome):
            return await outcome()
        if isinstance(outcome, BaseException):
            raise outcome
        return outcome

    async def generate_content(self, *, model, contents, config):
        return await self._play(model, config, contents)

    async def embed_content(self, *, model, contents, config):
        return await self._play(model, config, contents)


def make(script, fallback=FALLBACK, call_timeout=5.0):
    models = FakeModels(script)
    genai_client = SimpleNamespace(aio=SimpleNamespace(models=models))
    client = GeminiClient(
        genai_client,
        model=PRIMARY,
        fallback_model=fallback,
        thinking_level="minimal",
        embedding_model=EMBED,
        embedding_dimensions=DIMS,
        call_timeout_seconds=call_timeout,
    )
    return client, models


def vectors(n=1, dims=DIMS):
    return SimpleNamespace(embeddings=[SimpleNamespace(values=[0.1] * dims) for _ in range(n)])


async def test_primary_success_uses_only_the_primary_model():
    client, models = make({PRIMARY: [ok("hello")]})
    result = await client.generate_structured("p", Out, Deadline(7))
    assert result == Out(answer="hello")
    assert models.calls == [PRIMARY]
    config = models.configs[0]
    assert config.response_schema is Out
    assert config.response_mime_type == "application/json"
    assert config.thinking_config.thinking_level == types.ThinkingLevel.MINIMAL
    assert config.automatic_function_calling.disable is True  # no tools are ever passed


@pytest.mark.parametrize(
    "first_failure",
    [
        pytest.param(err(ServerError, 503), id="overloaded-503"),
        pytest.param(err(ClientError, 429), id="rate-limited-429"),
        pytest.param(err(ServerError, 504), id="deadline-504"),
        pytest.param(err(ClientError, 404), id="model-retired-404"),
        pytest.param(SimpleNamespace(parsed=None), id="unparseable-output"),
    ],
)
async def test_a_transient_failure_moves_to_the_fallback_model(first_failure):
    client, models = make({PRIMARY: [first_failure], FALLBACK: [ok("from-fallback")]})
    result = await client.generate_structured("p", Out, Deadline(7))
    assert result.answer == "from-fallback"
    assert models.calls == [PRIMARY, FALLBACK]


async def test_a_timeout_moves_to_the_fallback_model():
    async def hang():
        await asyncio.sleep(30)

    client, models = make({PRIMARY: [hang], FALLBACK: [ok()]}, call_timeout=0.5)
    result = await client.generate_structured("p", Out, Deadline(7))
    assert result == Out(answer="fine")
    assert models.calls == [PRIMARY, FALLBACK]


async def test_both_models_failing_raises_unavailable():
    client, _ = make({PRIMARY: [err(ClientError, 429)], FALLBACK: [err(ServerError, 503)]})
    with pytest.raises(LlmUnavailable, match="503"):
        await client.generate_structured("p", Out, Deadline(7))


async def test_a_bad_request_is_not_retried_on_the_fallback():
    client, models = make({PRIMARY: [err(ClientError, 400)], FALLBACK: [ok()]})
    with pytest.raises(LlmRequestError):
        await client.generate_structured("p", Out, Deadline(7))
    assert models.calls == [PRIMARY]


@pytest.mark.parametrize("code", [401, 403])
async def test_auth_failures_are_not_retried(code):
    client, models = make({PRIMARY: [err(ClientError, code)], FALLBACK: [ok()]})
    with pytest.raises(LlmRequestError):
        await client.generate_structured("p", Out, Deadline(7))
    assert models.calls == [PRIMARY]


async def test_no_fallback_configured():
    client, models = make({PRIMARY: [err(ServerError, 503)]}, fallback=None)
    with pytest.raises(LlmUnavailable):
        await client.generate_structured("p", Out, Deadline(7))
    assert models.calls == [PRIMARY]


async def test_no_call_is_started_without_time_budget():
    client, models = make({PRIMARY: [ok()]})
    with pytest.raises(LlmUnavailable, match="no time left"):
        await client.generate_structured("p", Out, Deadline(0.1))
    assert models.calls == []


async def test_the_remaining_deadline_caps_each_call():
    async def hang():
        await asyncio.sleep(30)

    client, models = make({PRIMARY: [hang], FALLBACK: [ok()]}, call_timeout=30)
    started = asyncio.get_running_loop().time()
    with pytest.raises(LlmUnavailable):
        # ~0.6s total budget: the primary times out at the deadline, leaving none for the fallback.
        await client.generate_structured("p", Out, Deadline(0.6))
    assert asyncio.get_running_loop().time() - started < 2
    assert models.calls == [PRIMARY]


async def test_prompts_are_never_logged(caplog):
    client, _ = make({PRIMARY: [err(ServerError, 503)], FALLBACK: [err(ServerError, 503)]})
    with caplog.at_level(logging.DEBUG), pytest.raises(LlmUnavailable):
        await client.generate_structured("SENSITIVE-PROMPT-TEXT", Out, Deadline(7))
    assert "SENSITIVE-PROMPT-TEXT" not in caplog.text


# ---- embeddings ----


@pytest.mark.parametrize(
    ("task", "expected"), [("query", "RETRIEVAL_QUERY"), ("document", "RETRIEVAL_DOCUMENT")]
)
async def test_embed_returns_one_vector_per_text_with_the_right_task(task, expected):
    client, models = make({EMBED: [vectors(2)]})
    result = await client.embed(["a", "b"], task=task, deadline=Deadline(7))
    assert len(result) == 2 and all(len(v) == DIMS for v in result)
    assert models.configs[0].task_type == expected
    assert models.configs[0].output_dimensionality == DIMS


async def test_embed_retries_once_on_a_transient_error():
    client, models = make({EMBED: [err(ServerError, 503), vectors()]})
    assert len(await client.embed(["a"], task="query", deadline=Deadline(7))) == 1
    assert models.calls == [EMBED, EMBED]


async def test_embed_gives_up_after_its_retry():
    client, models = make({EMBED: [err(ServerError, 503), err(ServerError, 503)]})
    with pytest.raises(LlmUnavailable):
        await client.embed(["a"], task="query", deadline=Deadline(7))
    assert len(models.calls) == 2


async def test_embed_rejects_a_wrong_dimension():
    client, _ = make({EMBED: [vectors(1, dims=DIMS + 1)]})
    with pytest.raises(LlmRequestError, match="unexpected shape"):
        await client.embed(["a"], task="query", deadline=Deadline(7))


async def test_embed_rejects_a_wrong_count():
    client, _ = make({EMBED: [vectors(1)]})
    with pytest.raises(LlmRequestError):
        await client.embed(["a", "b"], task="query", deadline=Deadline(7))


async def test_embed_bad_request_is_not_retried():
    client, models = make({EMBED: [err(ClientError, 400), vectors()]})
    with pytest.raises(LlmRequestError):
        await client.embed(["a"], task="query", deadline=Deadline(7))
    assert models.calls == [EMBED]


# ---- regressions found against the live API ----


async def test_embed_sends_one_content_per_text_never_a_plain_string_list():
    # gemini-embedding-2 merges a list of plain strings into ONE vector; separate
    # Content objects are what yield one vector per text.
    client, models = make({EMBED: [vectors(3)]})
    await client.embed(["a", "b", "c"], task="document", deadline=Deadline(7))
    sent = models.contents[0]
    assert len(sent) == 3
    assert all(isinstance(c, types.Content) for c in sent)
    assert [c.parts[0].text for c in sent] == ["a", "b", "c"]


async def test_a_dropped_connection_moves_to_the_fallback_model():
    dropped = httpx.RemoteProtocolError("Server disconnected without sending a response.")
    client, models = make({PRIMARY: [dropped], FALLBACK: [ok("recovered")]})
    result = await client.generate_structured("p", Out, Deadline(7))
    assert result.answer == "recovered"
    assert models.calls == [PRIMARY, FALLBACK]


async def test_network_errors_never_escape_as_raw_httpx_exceptions():
    client, _ = make(
        {PRIMARY: [httpx.ConnectError("no route")], FALLBACK: [httpx.ReadError("reset")]}
    )
    with pytest.raises(LlmUnavailable, match="network error"):
        await client.generate_structured("p", Out, Deadline(7))


async def test_embed_retries_after_a_dropped_connection():
    dropped = httpx.RemoteProtocolError("Server disconnected without sending a response.")
    client, models = make({EMBED: [dropped, vectors()]})
    assert len(await client.embed(["a"], task="query", deadline=Deadline(7))) == 1
    assert models.calls == [EMBED, EMBED]


async def test_embed_network_failure_after_retry_is_unavailable_not_a_crash():
    client, _ = make({EMBED: [httpx.ConnectError("x"), httpx.ConnectError("y")]})
    with pytest.raises(LlmUnavailable, match="network error"):
        await client.embed(["a"], task="query", deadline=Deadline(7))
