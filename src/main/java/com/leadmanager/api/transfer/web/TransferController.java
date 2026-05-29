package com.leadmanager.api.transfer.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.leadmanager.api.common.api.ApiVersion;
import com.leadmanager.api.common.security.AuthenticatedUser;
import com.leadmanager.api.transfer.Transfer;
import com.leadmanager.api.transfer.TransferProposeCommand;
import com.leadmanager.api.transfer.TransferService;

import jakarta.validation.Valid;

/**
 * HTTP layer for {@link Transfer} aggregates.
 * <p>
 * The URL space spans two parents:
 * <ul>
 *   <li>{@code /jobs/{jobId}/transfers} — operations rooted in the
 *       parent job (propose a new one, list the history).</li>
 *   <li>{@code /transfers/{id}/<action>} — operations on an existing
 *       transfer (accept, decline, cancel).</li>
 * </ul>
 * Both families live in this controller because they share the
 * same {@link TransferService} + {@link TransferMapper} dependencies.
 * The class-level {@link RequestMapping} is the API version prefix
 * only; each method declares its own full path.
 * <p>
 * <b>Why state changes are {@code POST /transfers/{id}/<action>}</b>
 * rather than {@code PATCH /transfers/{id}}: same rationale as
 * {@code JobController} — each transition is a discrete server-side
 * operation with its own authorization (proposer-only cancel, vs.
 * candidate-only accept/decline) and side effects.
 */
@RestController
@RequestMapping(ApiVersion.V1)
public class TransferController {

    private final TransferService service;
    private final TransferMapper mapper;

    public TransferController(TransferService service, TransferMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /**
     * Propose a transfer of the given job to the candidate named in
     * the body.
     * <p>
     * Responses:
     * <ul>
     *   <li>{@code 201 Created} with a {@code Location} header and
     *       a {@link TransferResponse} body.</li>
     *   <li>{@code 400 Bad Request} on Bean Validation failure OR
     *       when {@code toUserId == caller}.</li>
     *   <li>{@code 401 Unauthorized} for missing/invalid bearer token.</li>
     *   <li>{@code 404 Not Found} when the caller is not the job's
     *       originator/assignee OR when {@code toUserId} does not exist.</li>
     *   <li>{@code 409 Conflict} with {@code JOB_STATE_TRANSITION_NOT_ALLOWED}
     *       when the job is not OPEN, OR
     *       {@code OPEN_TRANSFER_ALREADY_EXISTS} when another open
     *       proposal already covers this job.</li>
     * </ul>
     */
    @PostMapping("/jobs/{jobId}/transfers")
    public ResponseEntity<TransferResponse> propose(
            @AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable Long jobId,
            @Valid @RequestBody TransferProposeRequest request) {

        Transfer saved = service.propose(me.id(),
                new TransferProposeCommand(jobId, request.toUserId(), request.commissionPct()));

        return ResponseEntity
                .created(UriComponentsBuilder
                        .fromPath(ApiVersion.V1 + "/transfers/{id}")
                        .buildAndExpand(saved.getId())
                        .toUri())
                .body(mapper.toResponse(saved));
    }

    /**
     * Chain history for a job (newest-first). Visible to anyone who
     * can see the job (originator, current assignee, current
     * candidate); 404 otherwise.
     */
    @GetMapping("/jobs/{jobId}/transfers")
    public List<TransferResponse> historyForJob(
            @AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable Long jobId) {
        return mapper.toResponses(service.historyForJob(me.id(), jobId));
    }

    /**
     * Candidate accepts the transfer. Transfer → ACCEPTED, job →
     * ASSIGNED with the candidate as the new assignee.
     */
    @PostMapping("/transfers/{id}/accept")
    public TransferResponse accept(
            @AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable Long id) {
        return mapper.toResponse(service.accept(me.id(), id));
    }

    /**
     * Candidate declines. Transfer → DECLINED, job restored to the
     * {@code preTransferState} recorded at propose time.
     */
    @PostMapping("/transfers/{id}/decline")
    public TransferResponse decline(
            @AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable Long id) {
        return mapper.toResponse(service.decline(me.id(), id));
    }

    /**
     * Proposer rescinds. Transfer → CANCELLED, job restored to the
     * {@code preTransferState}.
     */
    @PostMapping("/transfers/{id}/cancel")
    public TransferResponse cancel(
            @AuthenticationPrincipal AuthenticatedUser me,
            @PathVariable Long id) {
        return mapper.toResponse(service.cancel(me.id(), id));
    }
}
