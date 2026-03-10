package com.stablemock.core.resolver;

import com.stablemock.U;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestContextResolverHierarchyTest {

    @U(urls = { "https://parent.example" })
    static class ParentWithU {
    }

    @U(urls = { "https://child.example" })
    static class ChildWithU extends ParentWithU {
    }

    @Test
    void mergesParentThenChildUAnnotations() {
        U[] u = TestContextResolver.findAllUDeclaredOnClassHierarchy(ChildWithU.class);
        assertEquals(2, u.length);
        assertEquals("https://parent.example", u[0].urls()[0]);
        assertEquals("https://child.example", u[1].urls()[0]);
    }

    @U(urls = { "https://a.example" })
    @U(urls = { "https://b.example" })
    static class TwoOnLeaf {
    }

    @Test
    void repeatableOnSingleClassContributesBoth() {
        U[] u = TestContextResolver.findAllUDeclaredOnClassHierarchy(TwoOnLeaf.class);
        assertEquals(2, u.length);
    }
}
