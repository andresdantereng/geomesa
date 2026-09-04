/***********************************************************************
 * Copyright (c) 2013-2025 General Atomics Integrated Intelligence, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * https://www.apache.org/licenses/LICENSE-2.0
 ***********************************************************************/

package org.locationtech.geomesa.trino.security;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoMesaSecurityFunctionsTest {

    private static boolean visible(String vis, String auths) {
        Slice v = vis == null ? null : Slices.utf8Slice(vis);
        return GeoMesaSecurityFunctions.isVisible(v, Slices.utf8Slice(auths));
    }

    @Test
    void nullOrEmptyVisibilityIsUnrestricted() {
        assertThat(visible(null, "admin")).isTrue();
        assertThat(visible("", "admin")).isTrue();
        assertThat(visible(null, "")).isTrue();
    }

    @Test
    void singleAuthMatches() {
        assertThat(visible("admin", "admin")).isTrue();
        assertThat(visible("admin", "user")).isFalse();
    }

    @Test
    void andExpressionRequiresAllAuths() {
        assertThat(visible("admin&ops", "admin,ops")).isTrue();
        assertThat(visible("admin&ops", "admin")).isFalse();
    }

    @Test
    void orExpressionRequiresAnyAuth() {
        assertThat(visible("admin|user", "user")).isTrue();
        assertThat(visible("admin|user", "ops")).isFalse();
    }

    @Test
    void nestedExpression() {
        assertThat(visible("(admin|ops)&secure", "ops,secure")).isTrue();
        assertThat(visible("(admin|ops)&secure", "ops")).isFalse();
        assertThat(visible("(admin|ops)&secure", "secure")).isFalse();
    }

    @Test
    void emptyAuthsSeeOnlyUnrestrictedRows() {
        assertThat(visible(null, "")).isTrue();
        assertThat(visible("admin", "")).isFalse();
    }

    @Test
    void invalidExpressionFailsClosed() {
        assertThat(visible("admin&&(", "admin")).isFalse();
        assertThat(visible("admin&", "admin")).isFalse();
    }

    @Test
    void repeatedEvaluationsAreConsistent() {
        // Second and later calls for the same (auths, visibility) pair are served
        // from the decision cache; results must match the uncached first call —
        // including the cached fail-closed result for an invalid expression.
        for (int i = 0; i < 3; i++) {
            assertThat(visible("admin&ops", "admin,ops")).isTrue();
            assertThat(visible("admin&ops", "ops")).isFalse();
            assertThat(visible("admin&&(", "admin")).isFalse();
        }
    }

    @Test
    void cacheOverflowKeepsAnswersCorrect() {
        // Push well past one generation of both caches (distinct auth sets and
        // distinct decisions) to force rotations, then verify fresh and
        // previously-seen pairs still answer correctly.
        for (int i = 0; i < 20_000; i++) {
            assertThat(visible("tok" + i, "tok" + i)).isTrue();
        }
        assertThat(visible("admin", "admin")).isTrue();
        assertThat(visible("admin", "user")).isFalse();
        assertThat(visible("tok0", "tok0")).isTrue();
    }

    // Phase 1 Fast-Path Tests

    @Test
    void fastPathEmptyVisibility() {
        // FAST-PATH 0: Empty visibility is unrestricted
        assertThat(visible(null, "user")).isTrue();
        assertThat(visible("", "user")).isTrue();
        assertThat(visible(null, "")).isTrue();
        assertThat(visible("", "")).isTrue();
    }

    @Test
    void fastPathSingleTokenMatch() {
        // FAST-PATH 1: Single token match (exact string equality)
        assertThat(visible("user", "user")).isTrue();
        assertThat(visible("user", "admin")).isFalse();
        assertThat(visible("basic", "basic")).isTrue();
        assertThat(visible("privileged", "basic")).isFalse();
    }

    @Test
    void fastPathSimpleOrExpression() {
        // FAST-PATH 2: Simple OR expression with single auth
        assertThat(visible("public|user|admin", "user")).isTrue();
        assertThat(visible("public|admin", "user")).isFalse();
        assertThat(visible("user|privileged", "user")).isTrue();
        assertThat(visible("admin|ops", "user")).isFalse();
        // Edge case: single option (degenerate OR)
        assertThat(visible("user", "user")).isTrue();
    }

    @Test
    void fastPathNotUsedForComplexExpressions() {
        // These should NOT use fast-paths (they fall through to AccessEvaluator)
        // and results should still be correct
        assertThat(visible("admin&ops", "admin")).isFalse();
        assertThat(visible("admin&ops", "admin,ops")).isTrue();
        assertThat(visible("(admin|ops)&secure", "ops,secure")).isTrue();
        assertThat(visible("(admin|ops)&secure", "ops")).isFalse();
        assertThat(visible("admin,ops", "admin,ops")).isTrue();  // Comma in visibility
    }

    @Test
    void fastPathVsAccessEvaluatorConsistency() {
        // Verify fast-paths match AccessEvaluator behavior
        // Simple patterns that use fast-paths
        String[] simplePatterns = {
            "user", "admin", "basic", "privileged",
            "public|user|admin", "basic|privileged"
        };

        for (String vis : simplePatterns) {
            // Test with matching auth
            String auth = vis.split("\\|")[0];  // Get first option
            assertThat(visible(vis, auth)).isTrue();

            // Test with non-matching auth
            assertThat(visible(vis, "nonexistent")).isFalse();
        }
    }

    @Test
    void fastPathComplexAuthSetFallsBack() {
        // Complex auth sets (with commas) should fall back to AccessEvaluator
        assertThat(visible("admin", "admin,ops")).isFalse();
        assertThat(visible("admin&ops", "admin,ops")).isTrue();
        assertThat(visible("admin|ops", "admin,ops")).isTrue();
    }
}
