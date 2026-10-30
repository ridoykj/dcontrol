package com.dcontrol.controller.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.regex.Pattern;

/**
 * A number the phone may dial or text: {@code + 0-9} plus the separators {@code space ( ) - .}; without the
 * separators it must match {@code ^\+?[0-9]{2,20}$} (ws-v1.md, Phase 3). {@code *} and {@code #} are rejected
 * so a browser can never run MMI/USSD codes such as call forwarding on the phone. Null is invalid.
 */
@Documented
@Constraint(validatedBy = DialNumber.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DialNumber {

    int MAX_RAW = 40;

    String message() default "must be a phone number of 2–20 digits, optionally starting with +";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<DialNumber, String> {

        private static final Pattern SEPARATORS = Pattern.compile("[ ().\\-]");
        private static final Pattern ALLOWED = Pattern.compile("^[+0-9 ().\\-]+$");
        private static final Pattern DIGITS = Pattern.compile("^\\+?[0-9]{2,20}$");

        /** The form forwarded to the phone: separators removed. Only call it on a valid value. */
        public static String normalize(String raw) {
            return SEPARATORS.matcher(raw).replaceAll("");
        }

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            return value != null
                    && value.length() <= MAX_RAW
                    && ALLOWED.matcher(value).matches()
                    && DIGITS.matcher(normalize(value)).matches();
        }
    }
}
