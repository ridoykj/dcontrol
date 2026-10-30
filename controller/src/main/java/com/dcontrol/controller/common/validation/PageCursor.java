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

/** Opaque paging token made by a phone: 1–256 printable ASCII chars, or null (ws-v1.md, Phase 3). */
@Documented
@Pattern(regexp = PageCursor.REGEX)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface PageCursor {

    String REGEX = "^[\\x20-\\x7e]{1,256}$";

    String message() default "must be 1–256 printable ASCII characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
