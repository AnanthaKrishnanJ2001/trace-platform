"""POST /internal/v1/investigate (contracts/openapi/internal-api.yaml)."""

from fastapi import APIRouter, Depends

from app.agents.orchestrator import OrchestratorAgent
from app.api.deps import get_orchestrator
from app.api.schemas import InvestigationRequest, InvestigationResult
from app.security import verify_internal_jwt

# Every /internal/v1 route requires the internal JWT.
router = APIRouter(dependencies=[Depends(verify_internal_jwt)])


@router.post(
    "/investigate",
    response_model=InvestigationResult,
    response_model_exclude_none=True,  # the contract omits absent optional fields
)
async def investigate(
    body: InvestigationRequest,
    orchestrator: OrchestratorAgent = Depends(get_orchestrator),
) -> InvestigationResult:
    return await orchestrator.investigate(body)
