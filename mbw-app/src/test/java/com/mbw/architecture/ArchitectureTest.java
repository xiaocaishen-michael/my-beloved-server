package com.mbw.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Global architecture rules guarding module boundaries.
 *
 * <p>Module-specific rules live in each module's test sources. This class only
 * holds invariants that must hold across all business modules.
 */
@AnalyzeClasses(packages = "com.mbw")
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainPackagesShouldNotDependOnFrameworkAnnotations = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..")
            .allowEmptyShould(true);
}
