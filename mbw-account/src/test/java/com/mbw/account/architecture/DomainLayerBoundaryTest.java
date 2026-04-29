package com.mbw.account.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Locks down the {@code com.mbw.account.domain} layer boundary in CI:
 * any future change that pulls Spring / JPA / Jackson into a domain
 * class fails this test before review.
 *
 * <p>This is the runtime complement to the package-info-level
 * convention noted in meta {@code modular-strategy.md} § "层间依赖
 * 方向". A grep at PR time would catch the same drift, but ArchUnit
 * makes the rule executable so it cannot rot.
 */
class DomainLayerBoundaryTest {

    private static final JavaClasses ACCOUNT_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.mbw.account");

    @Test
    void domain_layer_should_not_depend_on_spring() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.mbw.account.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..");

        rule.check(ACCOUNT_CLASSES);
    }

    @Test
    void domain_layer_should_not_depend_on_jpa() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.mbw.account.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("jakarta.persistence..", "javax.persistence..");

        rule.check(ACCOUNT_CLASSES);
    }

    @Test
    void domain_layer_should_not_depend_on_jackson() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.mbw.account.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("com.fasterxml.jackson..");

        rule.check(ACCOUNT_CLASSES);
    }

    @Test
    void domain_layer_should_not_depend_on_servlet_or_web_layers() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("com.mbw.account.domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "jakarta.servlet..",
                        "com.mbw.account.web..",
                        "com.mbw.account.infrastructure..",
                        "com.mbw.account.application..");

        rule.check(ACCOUNT_CLASSES);
    }
}
