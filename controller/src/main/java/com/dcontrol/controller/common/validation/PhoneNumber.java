package com.dcontrol.controller.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A phone number as a phone stores it: 1–64 chars without control characters (ws-v1.md, Phase 3). SMS
 * senders can be alphanumeric, so nothing else is checked. Null is valid; add {@code @NotNull} where needed.
 */
@Documented
@Pattern(regexp = PhoneNumber.REGEX)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PhoneNumber {

    String REGEX = "^[^\\p{Cntrl}]{1,64}$";

    String message() default "must be 1–64 characters without control characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
