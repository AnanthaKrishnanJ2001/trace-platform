from fastapi import Request

from app.agents.orchestrator import OrchestratorAgent


def get_orchestrator(request: Request) -> OrchestratorAgent:
    return request.app.state.resources.orchestrator
