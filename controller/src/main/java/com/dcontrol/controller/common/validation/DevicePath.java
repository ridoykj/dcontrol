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

/** Absolute POSIX path on a phone: starts with {@code /}, 1–4096 chars, no NUL (ws-v1.md, Phase 2). */
@Documented
@NotNull
@Pattern(regexp = DevicePath.REGEX)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DevicePath {

    String REGEX = "^/[^\\x00]{0,4095}$";

    String message() default "must be an absolute path of at most 4096 characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
