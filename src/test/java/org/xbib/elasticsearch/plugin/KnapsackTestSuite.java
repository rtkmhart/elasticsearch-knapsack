package org.xbib.elasticsearch.plugin;

import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.xbib.elasticsearch.plugin.knapsack.KnapsackExportTests;
import org.xbib.elasticsearch.plugin.knapsack.KnapsackImportTests;
import org.xbib.elasticsearch.plugin.knapsack.KnapsackSimpleTests;
import org.xbib.elasticsearch.plugin.knapsack.KnapsackSplitTests;
import org.xbib.elasticsearch.plugin.knapsack.cpio.KnapsackCpioTests;
import org.xbib.elasticsearch.plugin.knapsack.tar.KnapsackTarTests;
import org.xbib.elasticsearch.plugin.knapsack.zip.KnapsackZipTests;


@RunWith(Suite.class)
@Suite.SuiteClasses({
        KnapsackSimpleTests.class,
        KnapsackExportTests.class,
        KnapsackImportTests.class,
        KnapsackTarTests.class,
        KnapsackZipTests.class,
        KnapsackCpioTests.class,
        KnapsackSplitTests.class
})
public class KnapsackTestSuite {

}
