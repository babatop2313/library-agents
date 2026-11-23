package com.example.library.shared;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Variante de {@link UseCase} pour un use case qui n'accède à aucune ressource transactionnelle (ex. lecture des
 * claims du JWT) : il s'exécute hors transaction et n'emprunte donc pas de connexion à la base.
 * Même compromis que {@link UseCase} : la couche application n'importe que cette annotation, aucune API Spring.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public @interface NonTransactionalUseCase {
}
