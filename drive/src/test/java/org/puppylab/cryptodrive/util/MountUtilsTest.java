package org.puppylab.cryptodrive.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import org.cryptomator.jfuse.api.Fuse;
import org.junit.jupiter.api.Test;
import org.puppylab.cryptodrive.core.fs.MirrorFileSystem;

public class MountUtilsTest {

    @Test
    void testMount() throws Exception {
        var fs = new MirrorFileSystem(Path.of("../test-mirror-fs").toAbsolutePath(), Fuse.builder().errno());
        try (var _ = MountUtils.mount("R:", fs, "Mirror Volume")) {
            assertTrue(Files.isDirectory(Path.of("R:\\document\\empty")));
            assertFalse(Files.isDirectory(Path.of("R:\\document\\not-exist")));
            Path readme = Path.of("R:\\README.txt");
            assertEquals("This is a README file.", Files.readString(readme));
            Path today = Path.of("R:\\today.txt");
            String str = "Today is " + LocalDate.now();
            Files.writeString(today, str);
            // read:
            assertEquals(str, Files.readString(today));
            Thread.sleep(60_000);
        }
    }

}
