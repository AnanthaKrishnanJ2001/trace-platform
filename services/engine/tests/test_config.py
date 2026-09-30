from app.config import get_settings
from app.llm.gemini import GeminiClient


def test_a_hung_primary_call_leaves_time_for_the_fallback():
    # Live run: a 5s cap on a 7s budget left the fallback ~1s, so failover never succeeded.
    settings = get_settings()
    assert settings.llm_call_timeout_seconds <= settings.investigation_deadline_seconds / 2
    assert settings.investigation_deadline_seconds < 8  # Spring Boot Core times out at 8s


def test_the_seeding_script_can_override_the_per_call_timeout():
    settings = get_settings()
    default = GeminiClient.from_settings(settings)
    long = GeminiClient.from_settings(settings, call_timeout_seconds=30)
    assert default._call_timeout == settings.llm_call_timeout_seconds
    assert long._call_timeout == 30
