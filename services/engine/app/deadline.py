"""A time budget for one investigation, shared by everything that can be slow."""

import time
from collections.abc import Callable


class Deadline:
    def __init__(self, seconds: float, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self._expires_at = clock() + seconds

    def remaining(self) -> float:
        return max(0.0, self._expires_at - self._clock())

    @property
    def expired(self) -> bool:
        return self.remaining() <= 0
