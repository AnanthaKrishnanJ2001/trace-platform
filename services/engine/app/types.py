"""Value types shared across the API, tools and agents."""

from decimal import Decimal
from typing import Annotated

from pydantic import PlainSerializer

# Decimal inside the engine so money arithmetic never drifts; serializes as a
# plain JSON number, which is what the contract specifies.
Money = Annotated[Decimal, PlainSerializer(float, return_type=float, when_used="json")]
