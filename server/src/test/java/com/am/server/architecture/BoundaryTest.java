package com.am.server.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * 机械分层守卫。失败时请先读 {@code docs/architecture/LAYERS.md} 再改依赖方向。
 */
class BoundaryTest {

    private static final String[] PERSISTENCE_MODEL_PACKAGES = {
        "com.am.server.domain..",
        "com.am.server.insight.domain..",
        "com.am.server.system.domain..",
    };

    private static final String[] HTTP_ADAPTER_PACKAGES = {
        "com.am.server.web..",
        "com.am.server.agent.api..",
        "com.am.server.insight.web..",
        "com.am.server.system.web..",
    };

    @Test
    void persistence_model_must_not_depend_on_http_adapter_packages() {
        var classes =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("com.am.server");

        ArchRule rule =
                noClasses()
                        .that()
                        .resideInAnyPackage(PERSISTENCE_MODEL_PACKAGES)
                        .should()
                        .onlyDependOnClassesThat()
                        .resideOutsideOfPackages(HTTP_ADAPTER_PACKAGES)
                        .because(
                                "VIOLATION: persistence model imports HTTP adapter layer — domain cannot"
                                    + " depend on web/agent.api/insight.web/system.web packages. See"
                                    + " docs/architecture/LAYERS.md");

        rule.check(classes);
    }
}
