---
name: add-webmvc-test-with-jwt-auth
description: Use when writing a `@WebMvcTest` for a controller protected by the JWT bearer filter chain. Covers the exact `@Import` list (forgetting one bean silently breaks security tests so they pass for the wrong reason), the `@MockBean JwtService` + service-tier mock pattern, the standard five test cases (401 no-token, 401 bad-token, 400 validation, business 4xx, 200/201 happy), and the `setField` reflection helper for stubbing `BaseEntity` fields like `id` and `createdAt`. Trigger when the user asks to add a controller test, test an endpoint, write a `@WebMvcTest`, or any controller test that exercises auth.
---

# Writing a `@WebMvcTest` against the JWT-protected filter chain

A controller test that passes without exercising real `SecurityConfig` proves nothing — Spring's default test slice silently disables security, so a 200 in the test does not mean the endpoint is actually reachable in production. This skill is the project's working recipe for a test that proves what it appears to prove.

## The annotation block (verbatim)

```java
@WebMvcTest(XController.class)
@Import({
        XMapperImpl.class,                 // MapStruct generated impl
        GlobalExceptionHandler.class,      // RFC 7807 mapping
        SecurityConfig.class,              // real filter chain
        JwtAuthFilter.class,               // promotes Bearer token to SecurityContext
        JwtAuthenticationEntryPoint.class  // RFC 7807 401 body
})
class XControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private JwtService jwtService;     // the chain wants it; tests mock it
    @MockBean private XService xService;         // your domain dep
    ...
}
```

**Why each `@Import` is non-optional:**

| Bean | Drop it → |
|---|---|
| `XMapperImpl` | `NoSuchBeanDefinitionException` — MapStruct's generated `*Impl` is not auto-discovered by `@WebMvcTest`. |
| `GlobalExceptionHandler` | Spring returns its default 500 HTML page on `ApiException` — tests asserting on `$.code` pass for the wrong reason (404 from the path, not from your handler). |
| `SecurityConfig` | Spring uses its default test security (everything permitted). 401 cases will see 200 and miss the regression. |
| `JwtAuthFilter` | The chain has no way to authenticate; even a valid Bearer header gives 401. Happy-path tests fail. |
| `JwtAuthenticationEntryPoint` | 401 bodies are Spring's default (empty), not RFC 7807. `jsonPath("$.code")` assertions silently fail. |

## The five canonical test cases

Every endpoint that mutates or returns user-scoped data should have all five:

```java
private static final String VALID_TOKEN = "valid.jwt.token";
private static final long PRINCIPAL_USER_ID = 7L;

@Test void X_returns401_whenAuthHeaderMissing() throws Exception {
    mockMvc.perform(get(ENDPOINT))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Content-Type",
                    Matchers.containsString("application/problem+json")))
            .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));
}

@Test void X_returns401_whenTokenIsInvalid() throws Exception {
    when(jwtService.parseAccessToken(eq("bogus")))
            .thenThrow(new InvalidTokenException("invalid token", new RuntimeException()));
    mockMvc.perform(get(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer bogus"))
            .andExpect(status().isUnauthorized());
}

@Test void X_returns400_whenValidationFails() throws Exception {
    when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
    mockMvc.perform(post(ENDPOINT)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")) // missing required fields
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
}

@Test void X_returns4xx_whenServiceThrowsApiException() throws Exception {
    when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
    when(xService.doThing(any()))
            .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "..."));
    mockMvc.perform(...)
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
}

@Test void X_returns2xx_andBody_whenValid() throws Exception {
    when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
    when(xService.doThing(any())).thenReturn(stubEntity());
    mockMvc.perform(...)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.field").value(...));
}
```

## The `setField` reflection helper

`BaseEntity#id` and `#createdAt` are populated by JPA at flush time — there's no public setter. In a `@WebMvcTest` you need to stub them by hand. Drop this helper at the bottom of every controller test:

```java
private static void setField(Object target, String name, Object value) {
    try {
        java.lang.reflect.Field f = findField(target.getClass(), name);
        f.setAccessible(true);
        f.set(target, value);
    } catch (ReflectiveOperationException e) {
        throw new AssertionError("Could not set " + name, e);
    }
}

private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
    Class<?> c = type;
    while (c != null) {
        try { return c.getDeclaredField(name); }
        catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
    }
    throw new NoSuchFieldException(name);
}
```

Use it like: `setField(stub, "id", 99L);` Walks the superclass chain so `BaseEntity` fields are reachable.

## Running just this test

```bash
./mvnw test -Dtest=XControllerTest
```

A clean PR-ready run is 4-5 cases in under 10 seconds per controller. The Spring context start is the long pole; the test cases themselves are sub-second.

## Anti-patterns to refuse

- **`@AutoConfigureMockMvc(addFilters = false)`** — disables the whole point of the test. If a Bearer token isn't required to reach the controller in the test, the auth contract isn't being verified.
- **Authentication via `@WithMockUser`** — bypasses `JwtAuthFilter`. Use the real filter + a mocked `JwtService`.
- **Asserting `200 OK` without asserting body shape.** Status codes are necessary but not sufficient; the regression that bites in production is "the field renamed under your feet."

## Related

- `lead-manager-vertical-slice` — sub-step c is when this skill applies.
- `add-rfc7807-error` — to understand what `GlobalExceptionHandler` maps and why importing it matters.
- `enforce-ownership-with-404` — when the controller acts on per-user resources, the "business 4xx" case usually means a 404 propagated from an ownership check.
