package com.orangecode.tianmu;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class BusinessArchitectureTest {
    @Test void interactionAndFollowReadsDoNotCreateServiceCycles() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests()).importPackages("com.orangecode.tianmu");
        noClasses().that().haveSimpleName("LikeServiceImpl").or().haveSimpleName("CoinServiceImpl")
                .or().haveSimpleName("FavoriteServiceImpl").or().haveSimpleName("BulletServiceImpl")
                .should().dependOnClassesThat().haveSimpleName("VideoService").check(classes);
        noClasses().that().haveSimpleName("FollowServiceImpl")
                .should().dependOnClassesThat().haveSimpleName("UserService").check(classes);
    }
}
