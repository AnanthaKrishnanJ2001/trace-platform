package com.trace.orchestrator.dto.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CurrencyValidatorTest {

    private final CurrencyValidator validator = new CurrencyValidator();

    @ParameterizedTest
    @ValueSource(strings = {"INR", "USD", "EUR"})
    void isValid_acceptsRealIso4217Codes(String code) {
        assertThat(validator.isValid(code, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZZZ", "US", "1NR", "rupees"})
    void isValid_rejectsCodesNotInIso4217(String code) {
        assertThat(validator.isValid(code, null)).isFalse();
    }
}
