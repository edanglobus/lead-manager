package com.leadmanager.api.transfer.web;

import java.util.List;

import org.mapstruct.Mapper;

import com.leadmanager.api.transfer.Transfer;

/**
 * Entity → response converter for {@link Transfer}.
 * <p>
 * Field names match 1:1 across the boundary, so MapStruct generates
 * the implementation without explicit {@code @Mapping} hints.
 * {@code preTransferState} is exposed on the wire so a UI can render
 * the "if I decline, the job will revert to ..." prompt without a
 * second round-trip.
 * <p>
 * The propose request → service command projection is small enough
 * to do inline in the controller; the only mapper-worthy shape is
 * the outbound response.
 */
@Mapper
public interface TransferMapper {

    TransferResponse toResponse(Transfer transfer);

    List<TransferResponse> toResponses(List<Transfer> transfers);
}
