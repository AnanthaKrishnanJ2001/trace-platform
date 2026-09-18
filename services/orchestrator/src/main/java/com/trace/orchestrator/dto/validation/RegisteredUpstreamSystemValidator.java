package com.trace.orchestrator.dto.validation;

import com.trace.orchestrator.config.UpstreamSystemsProperties;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Backs {@link RegisteredUpstreamSystem}, checking against the configured
 * allow-list ({@link UpstreamSystemsProperties}) rather than a hardcoded set,
 * so ops can register a new upstream system without a code change.
 */
@Component
public class RegisteredUpstreamSystemValidator implements ConstraintValidator<RegisteredUpstreamSystem, String> {

    private final UpstreamSystemsProperties upstreamSystemsProperties;

    @Autowired
    public RegisteredUpstreamSystemValidator(UpstreamSystemsProperties upstreamSystemsProperties) {
        this.upstreamSystemsProperties = upstreamSystemsProperties;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return upstreamSystemsProperties.getCodes().contains(value);
    }
}
