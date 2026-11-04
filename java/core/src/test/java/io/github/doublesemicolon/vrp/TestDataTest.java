package io.github.doublesemicolon.vrp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Java のテストから言語共通のテストデータが見えていること。
 *
 * <p>#17 以降の移植は全てこのデータで Python 版との一致を確かめるので、
 * 道筋だけ先に通しておく。件数は Python の test_every_case_file_is_loaded と同じ値で縛る。
 */
class TestDataTest {

    @Test
    void sharedCasesAreVisible() throws IOException {
        List<String> names;
        try (Stream<Path> files = Files.list(TestData.cases())) {
            names = files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .sorted()
                    .toList();
        }
        assertEquals(11, names.size(), () -> "testdata/cases の JSON: " + names);
        assertTrue(names.contains("toy5.json") && names.contains("toy8.json"));
    }

    @Test
    void cvrplibInstancesAreVisible() {
        Path dir = TestData.root().resolve("cvrplib");
        for (String name : List.of("X-n101-k25", "X-n106-k14")) {
            assertTrue(Files.isRegularFile(dir.resolve(name + ".vrp")), name + ".vrp");
            assertTrue(Files.isRegularFile(dir.resolve(name + ".sol")), name + ".sol");
        }
    }
}
