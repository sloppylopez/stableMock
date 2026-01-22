package example.inheritance;

import com.stablemock.spring.BaseStableMockTest;

public abstract class BaseTestFeature extends BaseOpenApiTestFeature {
    // Mimic intermediate class in 3-level inheritance chain
    // GetAvailabilityV2Test -> BaseTestFeature -> BaseOpenApiTestFeature -> BaseStableMockTest
}
