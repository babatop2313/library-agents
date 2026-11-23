package com.example.library.config;

import java.util.List;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuse le démarrage si un secret obligatoire n'est pas défini (aucune valeur par défaut).
 * <ul>
 *   <li>Seuls les noms de la convention du projet sont acceptés : {@code JWT_SECRET}, {@code DB_USER},
 *       {@code DB_PASSWORD}. Fournir directement la propriété Spring (par exemple
 *       {@code SPRING_DATASOURCE_PASSWORD}) ne suffit pas.</li>
 *   <li>Une valeur vide ou blanche compte comme manquante.</li>
 *   <li>{@code REDIS_PASSWORD} n'est volontairement pas vérifié : il est facultatif (vide en local, Redis sans
 *       mot de passe).</li>
 *   <li>S'exécute en dernier, une fois la configuration (application.yml, .properties, variables) chargée.</li>
 *   <li>S'exécute aussi pour chaque contexte de test ({@code @SpringBootTest} et slices) : les valeurs y
 *       viennent de {@code src/test/resources/application.properties}.</li>
 *   <li>Les valeurs n'apparaissent jamais dans les messages ni les logs : seuls les noms y figurent.</li>
 * </ul>
 */
public class RequiredEnvironmentVariables implements EnvironmentPostProcessor, Ordered {

    static final List<String> REQUIRED = List.of("JWT_SECRET", "DB_USER", "DB_PASSWORD");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> missing = REQUIRED.stream()
                .filter(name -> {
                    String value = environment.getProperty(name);
                    return value == null || value.isBlank();
                })
                .toList();
        if (!missing.isEmpty()) {
            throw new MissingEnvironmentVariablesException(missing);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
