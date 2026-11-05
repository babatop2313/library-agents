// architecture: <NOM>
// Généré par la Claude Factory (/init-architecture). Ne pas modifier les règles : corriger le code.
// La première ligne doit rester « // architecture: <nom> » : le hook session-start.sh s'en sert
// pour détecter un projet initialisé avec une autre architecture que celle de factory.json.
package __BASE_PACKAGE__;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "__BASE_PACKAGE__", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    // Une règle par interdit de la section « Règle de dépendance ». Exemple :
    @ArchTest
    static final ArchRule example_rule = noClasses().that().resideInAPackage("..<couche_interne>..")
        .should().dependOnClassesThat().resideInAnyPackage("..<couche_externe>..")
        .because("<raison, en français>");
}
