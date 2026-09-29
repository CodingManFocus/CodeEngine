package kr.codenamemc.codeengine.intelligence;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PaperApiVersionTest {
    @Test void modernPublishedCoordinatePinsTheExactBuildAndChannel() {
        for (String channel : new String[]{"alpha", "beta", "rc", "stable"}) {
            String coordinate = "26.2.build.124-" + channel;
            PaperApiVersion version = PaperApiVersion.from(coordinate, "26.2", "Paper");
            assertEquals(coordinate, version.version());
            assertEquals("exact", version.compatibility());
        }
    }

    @Test void legacySnapshotExplicitlyReportsThatExactBuildIdentityIsUnavailable() {
        PaperApiVersion version = PaperApiVersion.from("1.21.11-R0.1-SNAPSHOT", "1.21.11", "git-Paper-95");
        assertEquals("1.21.11-R0.1-SNAPSHOT", version.version());
        assertEquals("compatible-snapshot", version.compatibility());
        assertTrue(version.detail().contains("cannot identify"));
    }

    @Test void mismatchedVersionsAndUnknownBuildChannelsDoNotGuess() {
        assertThrows(IllegalArgumentException.class,
            () -> PaperApiVersion.from("1.21.10-R0.1-SNAPSHOT", "1.21.11", "Paper"));
        assertThrows(IllegalArgumentException.class,
            () -> PaperApiVersion.from("26.2.build.124-stable", "26.3", "Paper 26.3.build.3-stable"));
        assertThrows(IllegalArgumentException.class,
            () -> PaperApiVersion.from("26.2", "26.2", "26.2-124-main@abcdef"));
        assertThrows(IllegalArgumentException.class,
            () -> PaperApiVersion.from("26.2.build.124-custom", "26.2", "Paper"));
        assertThrows(IllegalArgumentException.class,
            () -> new PaperApiVersion("[26.2.build,)", "exact", "invalid"));
    }

    @Test void explicitCoordinateInServerDescriptionCanBeUsedWithoutInventingABuild() {
        assertEquals("26.1.2.build.23-alpha",
            PaperApiVersion.from("unknown", "26.1.2", "Paper 26.1.2.build.23-alpha (MC: 26.1.2)").version());
        assertThrows(IllegalArgumentException.class,
            () -> PaperApiVersion.from("unknown", "26.1.2", "26.1.2.build.23-alpha 26.1.2.build.24-beta"));
    }
}
