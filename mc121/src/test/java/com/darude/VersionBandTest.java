package com.darude;

import com.darude.platform.v121.DarudePlatformAdapter121;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VersionBandTest {
    @Test
    void adapterReports121Band() {
        assertEquals("1.21.x", new DarudePlatformAdapter121().versionBand());
    }
}
