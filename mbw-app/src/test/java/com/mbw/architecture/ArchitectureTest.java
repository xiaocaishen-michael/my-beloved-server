package com.mbw.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Global architecture rules guarding module boundaries (T19 — full
 * cross-module enforcement).
 *
 * <p>Module-internal rules (e.g. mbw-account's
 * {@code DomainLayerBoundaryTest}) live in each module's test sources;
 * this class only holds invariants that must hold across all business
 * modules and so are owned by the deployment unit.
 *
 * <p>The known business modules under {@code com.mbw} are listed
 * explicitly in {@link #BUSINESS_MODULE_PACKAGES}; {@code mbw-shared}
 * is the cross-module library and {@code mbw-app} is the deployment
 * unit, both of which are excluded from cross-module enforcement.
 * When a new business module is added (e.g. {@code mbw-pkm}), append
 * its package here so the cross-module rule starts protecting it.
 */
@AnalyzeClasses(packages = "com.mbw")
class ArchitectureTest {

    /**
     * Business-module package roots. Excludes {@code com.mbw.shared}
     * (cross-module library, all packages exported) and
     * {@code com.mbw.app} (deployment unit, may wire any module).
     */
    private static final String[] BUSINESS_MODULE_PACKAGES = {"com.mbw.account.."};

    @ArchTest
    static final ArchRule domainPackagesShouldNotDependOnFrameworkAnnotations = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "com.fasterxml.jackson..")
            .allowEmptyShould(true);

    /**
     * Web layer must not directly call infrastructure adapters — it
     * goes through the application layer (UseCases). Domain exception
     * imports are allowed (web maps them to HTTP responses), so this
     * rule narrowly targets {@code ..infrastructure..} only.
     */
    @ArchTest
    static final ArchRule webShouldNotDependOnInfrastructure = noClasses()
            .that()
            .resideInAPackage("..web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .allowEmptyShould(true);

    /**
     * Cross-module access must go through the {@code .api} package of
     * the target module. Each business module's non-api internals
     * (domain / application / infrastructure / web) are private to
     * that module.
     *
     * <p>{@code mbw-app} (deployment unit) is allowed to wire any
     * module's beans, so it is excluded here. {@code mbw-shared} is
     * the cross-module library and never the target of "module-private"
     * enforcement.
     */
    private static final DescribedPredicate<JavaClass> ACCOUNT_NON_API = resideInAPackage("com.mbw.account..")
            .and(resideOutsideOfPackage("com.mbw.account.api.."))
            .as("account internals (com.mbw.account.. excluding com.mbw.account.api..)");

    @ArchTest
    static final ArchRule otherBusinessModulesShouldOnlyTouchAccountViaApi = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.mbw.pkm..",
                    "com.mbw.billing..",
                    "com.mbw.work..",
                    "com.mbw.wealth..",
                    "com.mbw.health..",
                    "com.mbw.inspire..")
            .should()
            .dependOnClassesThat(ACCOUNT_NON_API)
            .allowEmptyShould(true);

    /**
     * The shared library must not depend on any business module —
     * dependencies flow business → shared, never the reverse.
     */
    @ArchTest
    static final ArchRule sharedMustNotDependOnBusinessModules = noClasses()
            .that()
            .resideInAPackage("com.mbw.shared..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(BUSINESS_MODULE_PACKAGES)
            .allowEmptyShould(true);
}
