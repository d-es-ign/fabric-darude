package com.darude;

import com.darude.platform.v261.DarudePlatformAdapter261;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionBandTest {
    @Test
    void adapterReports261Band() {
        assertEquals("26.1", new DarudePlatformAdapter261().versionBand());
    }
}
