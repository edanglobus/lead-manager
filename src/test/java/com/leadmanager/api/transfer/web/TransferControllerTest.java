package com.leadmanager.api.transfer.web;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
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
import com.leadmanager.api.job.JobState;
import com.leadmanager.api.transfer.Transfer;
import com.leadmanager.api.transfer.TransferProposeCommand;
import com.leadmanager.api.transfer.TransferService;
import com.leadmanager.api.transfer.TransferStatus;

/**
 * Web-layer test for {@link TransferController}. Imports the REAL
 * {@link SecurityConfig} + {@link JwtAuthFilter} +
 * {@link JwtAuthenticationEntryPoint} so the filter chain executes;
 * only {@link JwtService} and {@link TransferService} are mocked.
 */
@WebMvcTest(TransferController.class)
@Import({
        TransferMapperImpl.class,
        GlobalExceptionHandler.class,
        SecurityConfig.class,
        JwtAuthFilter.class,
        JwtAuthenticationEntryPoint.class
})
class TransferControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private JwtService jwtService;
    @MockBean private TransferService transferService;

    private static final String VALID_TOKEN = "valid.jwt.token";
    private static final long PRINCIPAL_USER_ID = 7L;
    private static final long JOB_ID      = 42L;
    private static final long TRANSFER_ID = 88L;

    // ===================================================================
    // POST /api/v1/jobs/{jobId}/transfers — propose
    // ===================================================================

    @Test
    void propose_returns401_whenAuthHeaderMissing() throws Exception {
        mockMvc.perform(post("/api/v1/jobs/" + JOB_ID + "/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validProposeBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type",
                        Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));

        verifyNoInteractions(transferService);
    }

    @Test
    void propose_returns400_whenCommissionMissing() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        String body = "{\"toUserId\": 99}";

        mockMvc.perform(post("/api/v1/jobs/" + JOB_ID + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()))
                .andExpect(jsonPath("$.errors[*].field", Matchers.hasItem("commissionPct")));
    }

    @Test
    void propose_returns400_whenCommissionOutOfRange() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);

        String body = "{\"toUserId\": 99, \"commissionPct\": 150.00}";

        mockMvc.perform(post("/api/v1/jobs/" + JOB_ID + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", Matchers.hasItem("commissionPct")));
    }

    @Test
    void propose_returns400_whenServiceReportsSelfProposal() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(transferService.propose(eq(PRINCIPAL_USER_ID), any(TransferProposeCommand.class)))
                .thenThrow(new ApiException(ErrorCode.VALIDATION_FAILED,
                        "Cannot propose a transfer to yourself"));

        mockMvc.perform(post("/api/v1/jobs/" + JOB_ID + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validProposeBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_FAILED.name()));
    }

    @Test
    void propose_returns404_whenServiceReportsJobNotVisibleOrCandidateMissing() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(transferService.propose(eq(PRINCIPAL_USER_ID), any(TransferProposeCommand.class)))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Job not found"));

        mockMvc.perform(post("/api/v1/jobs/" + JOB_ID + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validProposeBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    @Test
    void propose_returns409_whenOpenProposalAlreadyExists() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(transferService.propose(eq(PRINCIPAL_USER_ID), any(TransferProposeCommand.class)))
                .thenThrow(new ApiException(ErrorCode.OPEN_TRANSFER_ALREADY_EXISTS,
                        "An open transfer proposal already exists for this job"));

        mockMvc.perform(post("/api/v1/jobs/" + JOB_ID + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validProposeBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.OPEN_TRANSFER_ALREADY_EXISTS.name()));
    }

    @Test
    void propose_returns201_andLocationHeader_andBody_onSuccess() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        Transfer saved = stubTransfer(TransferStatus.PROPOSED, JobState.OPEN_GENERAL);
        when(transferService.propose(eq(PRINCIPAL_USER_ID), any(TransferProposeCommand.class)))
                .thenReturn(saved);

        mockMvc.perform(post("/api/v1/jobs/" + JOB_ID + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validProposeBody()))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        Matchers.endsWith("/api/v1/transfers/" + TRANSFER_ID)))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(TRANSFER_ID))
                .andExpect(jsonPath("$.jobId").value(JOB_ID))
                .andExpect(jsonPath("$.toUserId").value(99))
                .andExpect(jsonPath("$.commissionPct").value(20.00))
                .andExpect(jsonPath("$.status").value("PROPOSED"))
                .andExpect(jsonPath("$.preTransferState").value("OPEN_GENERAL"));

        verify(transferService).propose(eq(PRINCIPAL_USER_ID),
                argThat(cmd -> cmd.jobId().equals(JOB_ID)
                        && cmd.toUserId().equals(99L)
                        && cmd.commissionPct().compareTo(new BigDecimal("20.00")) == 0));
    }

    // ===================================================================
    // POST /api/v1/transfers/{id}/accept
    // ===================================================================

    @Test
    void accept_returns401_whenAuthHeaderMissing() throws Exception {
        mockMvc.perform(post("/api/v1/transfers/" + TRANSFER_ID + "/accept"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.name()));
    }

    @Test
    void accept_returns200_andSetsAccepted() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(transferService.accept(PRINCIPAL_USER_ID, TRANSFER_ID))
                .thenReturn(stubTransfer(TransferStatus.ACCEPTED, JobState.OPEN_GENERAL));

        mockMvc.perform(post("/api/v1/transfers/" + TRANSFER_ID + "/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        verify(transferService).accept(PRINCIPAL_USER_ID, TRANSFER_ID);
    }

    @Test
    void accept_returns409_whenServiceReportsIllegalTransition() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(transferService.accept(PRINCIPAL_USER_ID, TRANSFER_ID))
                .thenThrow(new ApiException(ErrorCode.TRANSFER_STATE_TRANSITION_NOT_ALLOWED,
                        "Transfer in state CANCELLED cannot transition to ACCEPTED"));

        mockMvc.perform(post("/api/v1/transfers/" + TRANSFER_ID + "/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.TRANSFER_STATE_TRANSITION_NOT_ALLOWED.name()));
    }

    @Test
    void accept_returns404_whenCallerNotCandidate() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(transferService.accept(PRINCIPAL_USER_ID, TRANSFER_ID))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Transfer not found"));

        mockMvc.perform(post("/api/v1/transfers/" + TRANSFER_ID + "/accept")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.RESOURCE_NOT_FOUND.name()));
    }

    // ===================================================================
    // POST /api/v1/transfers/{id}/decline + /cancel
    // ===================================================================

    @Test
    void decline_returns200_andSetsDeclined() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(transferService.decline(PRINCIPAL_USER_ID, TRANSFER_ID))
                .thenReturn(stubTransfer(TransferStatus.DECLINED, JobState.OPEN_TODAY));

        mockMvc.perform(post("/api/v1/transfers/" + TRANSFER_ID + "/decline")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.preTransferState").value("OPEN_TODAY"));
    }

    @Test
    void cancel_returns200_andSetsCancelled() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(transferService.cancel(PRINCIPAL_USER_ID, TRANSFER_ID))
                .thenReturn(stubTransfer(TransferStatus.CANCELLED, JobState.OPEN_GENERAL));

        mockMvc.perform(post("/api/v1/transfers/" + TRANSFER_ID + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancel_returns404_whenCallerNotProposer() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(transferService.cancel(PRINCIPAL_USER_ID, TRANSFER_ID))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Transfer not found"));

        mockMvc.perform(post("/api/v1/transfers/" + TRANSFER_ID + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound());
    }

    // ===================================================================
    // GET /api/v1/jobs/{jobId}/transfers
    // ===================================================================

    @Test
    void history_returns200_withMappedRows() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(transferService.historyForJob(PRINCIPAL_USER_ID, JOB_ID))
                .thenReturn(List.of(
                        stubTransfer(TransferStatus.PROPOSED, JobState.OPEN_GENERAL),
                        stubTransfer(TransferStatus.DECLINED, JobState.OPEN_GENERAL)));

        mockMvc.perform(get("/api/v1/jobs/" + JOB_ID + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("PROPOSED"))
                .andExpect(jsonPath("$[1].status").value("DECLINED"));
    }

    @Test
    void history_returns404_whenServiceReportsNoVisibility() throws Exception {
        when(jwtService.parseAccessToken(VALID_TOKEN)).thenReturn(PRINCIPAL_USER_ID);
        when(transferService.historyForJob(PRINCIPAL_USER_ID, JOB_ID))
                .thenThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "Job not found"));

        mockMvc.perform(get("/api/v1/jobs/" + JOB_ID + "/transfers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound());
    }

    // ---------- helpers ----------

    private static String validProposeBody() {
        return """
                {
                    "toUserId": 99,
                    "commissionPct": 20.00
                }
                """;
    }

    private static Transfer stubTransfer(TransferStatus status, JobState preState) {
        Transfer t = Transfer.builder()
                .jobId(JOB_ID)
                .fromUserId(PRINCIPAL_USER_ID)
                .toUserId(99L)
                .commissionPct(new BigDecimal("20.00"))
                .preTransferState(preState)
                .build();
        setField(t, "id", TRANSFER_ID);
        setField(t, "createdAt", Instant.parse("2026-05-27T10:00:00Z"));
        // Force status to whatever the test wants — bypass the entity
        // state-machine guard because the test cares about the wire
        // shape, not the transition path that got it there.
        setField(t, "status", status);
        if (status != TransferStatus.PROPOSED) {
            setField(t, "decidedAt", Instant.parse("2026-05-27T11:00:00Z"));
        }
        return t;
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
