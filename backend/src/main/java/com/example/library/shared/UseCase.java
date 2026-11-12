package com.example.library.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marque l'implémentation d'un use case : bean Spring transactionnel.
 * {@code @UseCase(readOnly = true)} pour un use case de consultation (transaction en lecture seule).
 * Compromis assumé : ce sont les seules dépendances Spring importées par la couche application.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@Transactional
public @interface UseCase {

    @AliasFor(annotation = Transactional.class, attribute = "readOnly")
    boolean readOnly() default false;
}
