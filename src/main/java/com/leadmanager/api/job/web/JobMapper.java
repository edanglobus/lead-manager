package com.leadmanager.api.job.web;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.leadmanager.api.job.Job;
import com.leadmanager.api.job.JobCreateCommand;

/**
 * Bidirectional converter between the HTTP layer and the service
 * layer for the {@code Job} aggregate.
 * <p>
 * MapStruct generates the implementation at compile time. The two
 * cross-layer renames worth flagging:
 * <ul>
 *   <li>The HTTP request takes a primitive-ish boolean {@code today};
 *       it maps to the service command's {@code today} field directly.
 *       The service derives the initial {@link com.leadmanager.api.job.JobState}
 *       from that — the wire never names a state.</li>
 *   <li>The response unpacks {@code Job.customerLocation} (a JTS
 *       {@link org.locationtech.jts.geom.Point}) into two scalar
 *       latitude/longitude fields. MapStruct can't infer that
 *       {@code Point.getY()} is "latitude" and {@code Point.getX()}
 *       is "longitude" since the JTS API exposes them as anonymous
 *       (X, Y) coordinates — so the mapping is spelt out explicitly,
 *       same pattern as
 *       {@link com.leadmanager.api.servicearea.web.ServiceAreaMapper}.</li>
 * </ul>
 */
@Mapper
public interface JobMapper {

    /** Inbound HTTP DTO → service-layer command. Field names match 1:1. */
    JobCreateCommand toCommand(JobRequest request);

    /**
     * Entity → public response. Point components are extracted
     * explicitly. {@code createdAt} comes from {@code BaseEntity}.
     */
    @Mapping(target = "customerLatitude",  expression = "java(job.getCustomerLocation().getY())")
    @Mapping(target = "customerLongitude", expression = "java(job.getCustomerLocation().getX())")
    JobResponse toResponse(Job job);

    /**
     * Entity → masked response for a transfer candidate. The two
     * privacy-sensitive fields are absent from
     * {@link MaskedJobResponse}, so MapStruct simply does not write
     * them — no nulls land on the wire. The lat/lng unpacking is
     * identical to {@link #toResponse(Job)} (rough geo is fine to
     * show; precise address is the part that's withheld).
     */
    @Mapping(target = "customerLatitude",  expression = "java(job.getCustomerLocation().getY())")
    @Mapping(target = "customerLongitude", expression = "java(job.getCustomerLocation().getX())")
    MaskedJobResponse toMaskedResponse(Job job);

    List<JobResponse> toResponses(List<Job> jobs);
}
