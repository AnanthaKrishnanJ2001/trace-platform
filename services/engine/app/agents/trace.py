"""Collects the agent_trace an investigation returns (one row per step, TDD §3.2)."""

from collections.abc import Callable
from datetime import datetime, timezone

from app.api.schemas import AgentName, AgentTraceStep


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class TraceRecorder:
    def __init__(self, clock: Callable[[], datetime] = _utc_now) -> None:
        self._clock = clock
        self._steps: list[AgentTraceStep] = []

    def record(
        self,
        agent: AgentName,
        step: str,
        tool: str | None = None,
        tool_input: dict | None = None,
        tool_output: dict | None = None,
    ) -> None:
        self._steps.append(
            AgentTraceStep(
                agent=agent,
                step=step,
                tool=tool,
                tool_input=tool_input,
                tool_output=tool_output,
                timestamp=self._clock(),
            )
        )

    @property
    def steps(self) -> list[AgentTraceStep]:
        return list(self._steps)
