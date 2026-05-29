package com.leadmanager.api.job.web;

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
import com.leadmanager.api.job.Job;
import com.leadmanager.api.job.JobAccess;
import com.leadmanager.api.job.JobService;
import com.leadmanager.api.job.JobVisibilityPolicy;

import jakarta.validation.Valid;

/**
 * HTTP layer for the {@link Job} aggregate.
 * <p>
 * One controller hosts all nine endpoints because they share a
 * dependency surface (one service + one mapper) and the URL space
 * is one resource family. Splitting "writes" vs "reads" would be
 * premature complexity.
 * <p>
 * <b>Controller contract</b> per CLAUDE.md: parse, convert, delegate,
 * respond. Authorization and the state-transition matrix live in the
 * service tier — this layer only supplies the authenticated user's
 * id and trusts the service to act on their behalf.
 * <p>
 * <b>Why state transitions are {@code POST /jobs/{id}/<action>}</b>
 * rather than {@code PATCH /jobs/{id}} with a partial body: each
 * transition is a discrete server-side operation with its own
 * authorization rule and side effects (future: ledger entries off
 * close, push notifications off start, …). RPC-shaped sub-resource
 * URLs make those operations grep-able in server logs and in the
 * mobile/web client code, which is more useful than REST purity here.
 */
@RestController
@RequestMapping(ApiVersion.V1 + "/jobs")
public class JobController {

    private final JobService service;
    private final JobMapper mapper;

    public JobController(JobService service, JobMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /**
     * Creates a lead with the authenticated user as the originator.
     *
     * Responses:
     * <ul>
     *   <li>{@code 201 Created} with a {@code Location} header and a
     *       {@link JobResponse} body.</li>
     *   <li>{@code 400 Bad Request} (RFC 7807) on Bean Validation failure.</li>
     *   <li>{@code 401 Unauthorized} when the bearer token is missing or invalid.</li>
     *   <li>{@code 404 Not Found} when the referenced category does
     *       not exist or is inactive.</li>
     * </ul>
     */
    @PostMapping
    public ResponseEntity<JobResponse> create(
            @AuthenticationPrincipal AuthenticatedUser me,
            @Valid @RequestBody JobRequest request) {

        Job saved = service.create(me.id(), mapper.toCommand(request));
        JobResponse body = mapper.toResponse(saved);

        return ResponseEntity
                .created(UriComponentsBuilder
                        .fromPath(ApiVersion.V1 + "/jobs/{id}")
                        .buildAndExpand(saved.getId())
                        .toUri())
                .body(body);
    }

    /**
     * Returns the job to a caller with any visibility on it.
     * <p>
     * Dispatches on {@link JobVisibilityPolicy.Visibility}:
     * <ul>
     *   <li>{@code FULL} (originator / current assignee) → full
     *       {@link JobResponse}.</li>
     *   <li>{@code MASKED} (transfer candidate with an open
     *       proposal) → {@link MaskedJobResponse}, with
     *       {@code customerPhone} and {@code customerAddress}
     *       absent from the JSON entirely.</li>
     *   <li>{@code NONE} — service already converted to 404
     *       {@code RESOURCE_NOT_FOUND} (no enumeration leak).</li>
     * </ul>
     * The return type is {@code ResponseEntity<?>} because the
     * two response shapes are distinct records. Both serialise to
     * application/json; only the field set differs.
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> findOne(@AuthenticationPrincipal AuthenticatedUser me,
                                     @PathVariable Long id) {
        JobAccess access = service.findOne(me.id(), id);
        return switch (access.visibility()) {
            case FULL   -> ResponseEntity.ok(mapper.toResponse(access.job()));
            case MASKED -> ResponseEntity.ok(mapper.toMaskedResponse(access.job()));
            case NONE   -> throw new IllegalStateException(
                    "service must never return NONE — should have thrown 404");
        };
    }

    /** Caller's own jobs as originator, newest-first. */
    @GetMapping("/mine/originated")
    public List<JobResponse> listMyOriginated(@AuthenticationPrincipal AuthenticatedUser me) {
        return mapper.toResponses(service.listMyOriginated(me.id()));
    }

    /** Caller's own jobs as current assignee, newest-first. */
    @GetMapping("/mine/assigned")
    public List<JobResponse> listMyAssigned(@AuthenticationPrincipal AuthenticatedUser me) {
        return mapper.toResponses(service.listMyAssigned(me.id()));
    }

    /**
     * Originator self-assigns one of their open leads.
     * Returns 200 with the updated job; 404 if the caller is not
     * the originator; 409 if the job is not in an OPEN_* state.
     */
    @PostMapping("/{id}/self-assign")
    public JobResponse selfAssign(@AuthenticationPrincipal AuthenticatedUser me,
                                  @PathVariable Long id) {
        return mapper.toResponse(service.selfAssign(me.id(), id));
    }

    /**
     * Current assignee starts work (ASSIGNED → IN_PROGRESS).
     * 404 if the caller is not the assignee; 409 otherwise.
     */
    @PostMapping("/{id}/start")
    public JobResponse start(@AuthenticationPrincipal AuthenticatedUser me,
                             @PathVariable Long id) {
        return mapper.toResponse(service.start(me.id(), id));
    }

    /**
     * Current assignee marks work complete (IN_PROGRESS → COMPLETED).
     * 404 if the caller is not the assignee; 409 otherwise.
     */
    @PostMapping("/{id}/complete")
    public JobResponse complete(@AuthenticationPrincipal AuthenticatedUser me,
                                @PathVariable Long id) {
        return mapper.toResponse(service.complete(me.id(), id));
    }

    /**
     * Originator confirms payment and closes the job
     * (COMPLETED → CLOSED_PAID). 404 if the caller is not the
     * originator; 409 otherwise.
     */
    @PostMapping("/{id}/close")
    public JobResponse close(@AuthenticationPrincipal AuthenticatedUser me,
                             @PathVariable Long id) {
        return mapper.toResponse(service.close(me.id(), id));
    }

    /**
     * Cancels a non-terminal job. Either side may cancel — both have
     * legitimate reasons. The body's {@code reason} is optional and,
     * when present, recorded on the transition row.
     * <p>
     * 404 if the caller is neither originator nor current assignee;
     * 409 if the job is already terminal.
     */
    @PostMapping("/{id}/cancel")
    public JobResponse cancel(@AuthenticationPrincipal AuthenticatedUser me,
                              @PathVariable Long id,
                              @Valid @RequestBody CancelRequest request) {
        return mapper.toResponse(service.cancel(me.id(), id, request.reason()));
    }
}
