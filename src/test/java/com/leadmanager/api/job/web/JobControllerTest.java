package com.leadmanager.api.job.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.leadmanager.api.common.exception.ApiException;
import com.leadmanager.api.common.exception.ErrorCode;
import com.leadmanager.api.common.exception.GlobalExceptionHandler;
import com.leadmanager.api.common.security.JwtAuthFilter;
import com.leadmanager.api.common.security.JwtAuthenticationEntryPoint;
import com.leadmanager.api.common.security.JwtService;
import com.leadmanager.api.common.security.SecurityConfig;
import com.leadmanager.api.job.Job;
import com.leadmanager.api.job.JobCreateCommand;
import com.leadmanager.api.job.JobService;
import com.leadmanager.api.job.JobState;

/**
 * Web-layer test for {@link JobController}. Imports the REAL
 * {@link SecurityConfig}, {@link JwtAuthFilter}, and
 * {@link JwtAuthenticationEntryPoint} so the test exercises the
 * actual filter chain — only {@link JwtService} and {@link JobService}
 * are mocked.
 */
@WebMvcTest(JobController.class)
@Import({
        JobMapperImpl.class,
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthFilter.class,
        JwtAuthenticationEntryPoint.class
})
class JobControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private JwtService jwtService;
    @MockBean private JobService jobService;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final long PRINCIPAL_USER_ID = 7L;
    private static final String ENDPOINT = "/api/v1/jobs";

    private static final GeometryFactory FACTORY =
            new GeometryFactory(new PrecisionModel(), 4326);

    // ===================================================================
    // POST /api/v1/jobs — create
    // ===================================================================

    @Test
    void create_returns401_whenAuthHeaderMissing() throws Exception {
        mockMvc.perform(post(ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type",
                        Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));

        verifyNoInteractions(jobService);
    }

    @Test
    void create_returns400_whenRequiredFieldMissing() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        String bodyMissingTitle = """
                {
                    "serviceCategoryId": 12,
                    "today": false,
                    "description": "x",
                    "customerName": "Jane",
                    "customerPhone": "+972-50-1",
                    "customerAddress": "Dizengoff 100",
                    "customerLatitude": 32.08,
                    "customerLongitude": 34.78,
                    "priceCents": 1000,
                    "currency": "USD"
                }
                """;

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyMissingTitle))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.errors[*].field", Matchers.hasItem("title")));

        verifyNoInteractions(jobService);
    }

    @Test
    void create_returns400_whenCurrencyMalformed() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        String bodyBadCurrency = validCreateBody().replace("\"USD\"", "\"usd\"");

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyBadCurrency))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", Matchers.hasItem("currency")));
    }

    @Test
    void create_returns404_whenServiceReportsCategoryMissing() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(jobService.create(eq(PRINCIPAL_USER_ID), any(JobCreateCommand.class)))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Service category not found"));

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @Test
    void create_returns201_andLocationHeader_andUnpacksLatLng() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        Job saved = stubJob(JobState.OPEN_GENERAL, null);
        when(jobService.create(eq(PRINCIPAL_USER_ID), any(JobCreateCommand.class))).thenReturn(saved);

        mockMvc.perform(post(ENDPOINT)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        Matchers.endsWith("/api/v1/jobs/99")))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.state").value("OPEN_GENERAL"))
                .andExpect(jsonPath("$.customerLatitude").value(32.08))
                .andExpect(jsonPath("$.customerLongitude").value(34.78))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.priceCents").value(15000));
    }

    // ===================================================================
    // GET /api/v1/jobs/{id} — findOne
    // ===================================================================

    @Test
    void findOne_returns401_whenAuthHeaderMissing() throws Exception {
        mockMvc.perform(get(ENDPOINT + "/99"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));
    }

    @Test
    void findOne_returns404_whenServiceReportsNotVisibleOrMissing() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(jobService.findOne(PRINCIPAL_USER_ID, 999L))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Job not found"));

        mockMvc.perform(get(ENDPOINT + "/999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @Test
    void findOne_returns200_whenVisibleToCaller() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(jobService.findOne(PRINCIPAL_USER_ID, 99L))
                .thenReturn(stubJob(JobState.IN_PROGRESS, 42L));

        mockMvc.perform(get(ENDPOINT + "/99")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(99))
                .andExpect(jsonPath("$.state").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.currentAssigneeUserId").value(42));
    }

    // ===================================================================
    // GET /api/v1/jobs/mine/originated  +  /mine/assigned
    // ===================================================================

    @Test
    void listMyOriginated_returns200_withMappedJobs() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(jobService.listMyOriginated(PRINCIPAL_USER_ID))
                .thenReturn(List.of(stubJob(JobState.OPEN_GENERAL, null)));

        mockMvc.perform(get(ENDPOINT + "/mine/originated")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(99));
    }

    @Test
    void listMyAssigned_returns200_withMappedJobs() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(jobService.listMyAssigned(PRINCIPAL_USER_ID))
                .thenReturn(List.of(stubJob(JobState.ASSIGNED, PRINCIPAL_USER_ID)));

        mockMvc.perform(get(ENDPOINT + "/mine/assigned")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].currentAssigneeUserId").value(PRINCIPAL_USER_ID));
    }

    // ===================================================================
    // State transitions — one happy + one error per endpoint
    // ===================================================================

    @Test
    void selfAssign_returns200_andDelegatesToService() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(jobService.selfAssign(PRINCIPAL_USER_ID, 99L))
                .thenReturn(stubJob(JobState.ASSIGNED, PRINCIPAL_USER_ID));

        mockMvc.perform(post(ENDPOINT + "/99/self-assign")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ASSIGNED"));

        verify(jobService).selfAssign(PRINCIPAL_USER_ID, 99L);
    }

    @Test
    void start_returns409_whenServiceReportsIllegalTransition() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(jobService.start(PRINCIPAL_USER_ID, 99L))
                .thenThrow(new ApiException(
                        ErrorCode.JOB_STATE_TRANSITION_NOT_ALLOWED,
                        "Job in state OPEN_GENERAL cannot transition to IN_PROGRESS"));

        mockMvc.perform(post(ENDPOINT + "/99/start")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.JOB_STATE_TRANSITION_NOT_ALLOWED.name()));
    }

    @Test
    void complete_returns200_andUpdatesState() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(jobService.complete(PRINCIPAL_USER_ID, 99L))
                .thenReturn(stubJob(JobState.COMPLETED, PRINCIPAL_USER_ID));

        mockMvc.perform(post(ENDPOINT + "/99/complete")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("COMPLETED"));
    }

    @Test
    void close_returns404_whenServiceReportsCallerNotOriginator() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(jobService.close(PRINCIPAL_USER_ID, 99L))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Job not found"));

        mockMvc.perform(post(ENDPOINT + "/99/close")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @Test
    void cancel_returns200_andPassesReasonThroughToService() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(jobService.cancel(eq(PRINCIPAL_USER_ID), eq(99L), eq("customer flake")))
                .thenReturn(stubJob(JobState.CANCELLED, null));

        mockMvc.perform(post(ENDPOINT + "/99/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"customer flake\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"));
    }

    @Test
    void cancel_acceptsEmptyBody_andForwardsNullReason() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(jobService.cancel(eq(PRINCIPAL_USER_ID), eq(99L), argThat(reason -> reason == null)))
                .thenReturn(stubJob(JobState.CANCELLED, null));

        mockMvc.perform(post(ENDPOINT + "/99/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void cancel_returns400_whenReasonExceedsLimit() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        String huge = "x".repeat(501);
        String body = "{\"reason\":\"" + huge + "\"}";

        mockMvc.perform(post(ENDPOINT + "/99/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    void transitionEndpoints_allReturn401_whenAuthHeaderMissing() throws Exception {
        // Spot-check one transition endpoint per HTTP method; the rest
        // go through the same filter chain so a single 401 here covers
        // the rest by construction (security tests above already exercise
        // the filter explicitly on /jobs and /jobs/{id}).
        for (String path : List.of("/99/self-assign", "/99/start", "/99/complete", "/99/close")) {
            mockMvc.perform(post(ENDPOINT + path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));
        }
    }

    // ---------- helpers ----------

    private static String validCreateBody() {
        return """
                {
                    "serviceCategoryId": 12,
                    "today": false,
                    "title": "Leaking faucet",
                    "description": "Kitchen sink, drips every 2 seconds",
                    "customerName": "Jane Doe",
                    "customerPhone": "+972-50-1234567",
                    "customerAddress": "Dizengoff 100, Tel Aviv",
                    "customerLatitude": 32.08,
                    "customerLongitude": 34.78,
                    "priceCents": 15000,
                    "currency": "USD"
                }
                """;
    }

    private static Job stubJob(JobState state, Long assignee) {
        Point loc = FACTORY.createPoint(new Coordinate(34.78, 32.08));
        loc.setSRID(4326);
        Job job = Job.builder()
                .originatorUserId(PRINCIPAL_USER_ID)
                .serviceCategoryId(12L)
                .state(state)
                .title("Leaking faucet")
                .description("Kitchen sink, drips every 2 seconds")
                .customerName("Jane Doe")
                .customerPhone("+972-50-1234567")
                .customerAddress("Dizengoff 100, Tel Aviv")
                .customerLocation(loc)
                .priceCents(15_000L)
                .currency("USD")
                .build();
        setField(job, "id", 99L);
        setField(job, "createdAt", Instant.parse("2026-05-27T08:00:00Z"));
        if (assignee != null) {
            setField(job, "currentAssigneeUserId", assignee);
        }
        return job;
    }

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
}
