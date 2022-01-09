package org.lflang.tests.compiler.lsp;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import org.eclipse.emf.common.util.URI;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.lflang.LFRuntimeModule;
import org.lflang.LFStandaloneSetup;
import org.lflang.Target;
import org.lflang.generator.GeneratorResult;
import org.lflang.generator.GeneratorResult.Status;
import org.lflang.generator.IntegratedBuilder;
import org.lflang.generator.LanguageServerErrorReporter;
import org.lflang.tests.LFTest;
import org.lflang.tests.TestRegistry;
import org.lflang.tests.TestRegistry.TestCategory;

/**
 * Test the code generator features that are required by the language server.
 */
class LspTests {

    private static final Random RANDOM = new Random(2101);
    private static final int MAX_RUNS_PER_TARGET_AND_CATEGORY = 3;

    /** The {@code IntegratedBuilder} instance whose behavior is to be tested. */
    private static final IntegratedBuilder builder = new LFStandaloneSetup(new LFRuntimeModule())
        .createInjectorAndDoEMFRegistration().getInstance(IntegratedBuilder.class);

    /** Test that the "Build and Run" functionality of the language server works. */
    @Test
    void buildAndRun() {
        LanguageServerErrorReporter.setClient(new TestLanguageClient());
        for (LFTest test : selectTests()) {
            TestReportProgress reportProgress = new TestReportProgress();
            GeneratorResult result = builder.run(
                URI.createFileURI(test.srcFile.toString()),
                true, reportProgress,
                () -> false
            );
            Assertions.assertFalse(reportProgress.failed());
            Assertions.assertEquals(Status.COMPILED, result.getStatus());
            Assertions.assertNotNull(result.getCommand());
            Assertions.assertEquals(result.getCommand().run(), 0);
        }
    }

    /**
     * Select {@code MAX_RUNS_PER_TARGET_AND_CATEGORY} tests from each group of tests.
     * @return A stratified sample of the integration tests.
     */
    private Set<LFTest> selectTests() {
        Set<LFTest> ret = new HashSet<>();
        for (Target target : Target.values()) {
            for (TestCategory category : TestCategory.values()) {
                Set<LFTest> registeredTests = TestRegistry.getRegisteredTests(target, category, false);
                if (registeredTests.size() == 0) continue;
                Set<Integer> selectedIndices = RANDOM.ints(0, registeredTests.size())
                    .limit(MAX_RUNS_PER_TARGET_AND_CATEGORY).collect(HashSet::new, HashSet::add, HashSet::addAll);
                int i = 0;
                for (LFTest t : registeredTests) {
                    if (selectedIndices.contains(i)) ret.add(t);
                    i++;
                }
            }
        }
        return ret;
    }
}
