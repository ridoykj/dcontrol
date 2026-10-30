package com.dcontrol.controller.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** One path segment: 1–255 chars, no {@code /} or NUL, not {@code .} or {@code ..}. */
@Documented
@NotNull
@Pattern(regexp = FileName.REGEX)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface FileName {

    String REGEX = "^(?!\\.{1,2}$)[^/\\x00]{1,255}$";

    String message() default "must be a file name of 1-255 characters without '/'";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
