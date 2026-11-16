package com.gibran.bikereader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateCheckerTest {

    @Test
    public void baseVersionStripsDevSuffix() {
        assertEquals("0.1", UpdateChecker.baseVersion("0.1-dev.7"));
        assertEquals("0.2", UpdateChecker.baseVersion("0.2"));
        assertEquals("0", UpdateChecker.baseVersion(null));
    }

    @Test
    public void compareVersionsIsNumericPerPart() {
        assertTrue(UpdateChecker.compareVersions("0.10", "0.9") > 0);
        assertTrue(UpdateChecker.compareVersions("0.2", "0.1") > 0);
        assertTrue(UpdateChecker.compareVersions("1.0", "1.0.1") < 0);
        assertEquals(0, UpdateChecker.compareVersions("0.1", "0.1.0"));
    }
}
