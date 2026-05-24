package com.leadmanager.api.common.api;

/**
 * Single source of truth for URL path prefixes per API major version.
 * Why: every external contract change must rev the version; hardcoding "/api/v1"
 * across controllers makes a future bump (v2 + parallel v1) painful and error-prone.
 */
public final class ApiVersion {

    public static final String V1 = "/api/v1";

    private ApiVersion() {
    }
}
