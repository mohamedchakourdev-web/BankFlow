package com.bankflow.auth.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = PasswordPolicyValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordPolicy {

    String message() default "Password must be at least 8 characters and include a letter and a number";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
