package example;

import com.stablemock.core.resolver.TestContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TestContextResolver#isSpringBootTest(Class)}.
 *
 * Regression for Bug 1: before the fix, {@code isAnnotationPresent()} was called
 * only on the concrete class, missing {@code @SpringBootTest} when it lives on a
 * base class. After the fix the hierarchy is walked explicitly.
 */
class TestContextResolverTest {

    @SpringBootTest
    static class BaseWithAnnotation {}

    static class SubWithoutAnnotation extends BaseWithAnnotation {}

    static class GrandchildWithoutAnnotation extends SubWithoutAnnotation {}

    static class NoAnnotation {}

    @Test
    void returnsTrueWhenAnnotationIsOnTheClassItself() {
        assertTrue(TestContextResolver.isSpringBootTest(BaseWithAnnotation.class));
    }

    @Test
    void returnsTrueWhenAnnotationIsOnDirectSuperclass() {
        assertTrue(TestContextResolver.isSpringBootTest(SubWithoutAnnotation.class));
    }

    @Test
    void returnsTrueWhenAnnotationIsOnGrandparentClass() {
        assertTrue(TestContextResolver.isSpringBootTest(GrandchildWithoutAnnotation.class));
    }

    @Test
    void returnsFalseWhenNoAnnotationInHierarchy() {
        assertFalse(TestContextResolver.isSpringBootTest(NoAnnotation.class));
    }
}
