package com.stablemock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableMockExtensionTest {

    @Test
    void newStyleInvocationDirMatchingRequiresBoundaryAfterIndex() {
        String prefix = "parallelParameterizedPlayback__i1";

        assertTrue(StableMockExtension.isNewStyleInvocationDirMatch("parallelParameterizedPlayback__i1", prefix));
        assertTrue(StableMockExtension.isNewStyleInvocationDirMatch("parallelParameterizedPlayback__i1__a1b2c3d4", prefix));
        assertFalse(StableMockExtension.isNewStyleInvocationDirMatch("parallelParameterizedPlayback__i10", prefix));
        assertFalse(StableMockExtension.isNewStyleInvocationDirMatch("parallelParameterizedPlayback__i10__ff00aa11", prefix));
        assertFalse(StableMockExtension.isNewStyleInvocationDirMatch("parallelParameterizedPlayback__i11", prefix));
    }
}
