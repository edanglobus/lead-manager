package com.leadmanager.api.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pins the {@link TransferStatus} transition matrix. The transfer
 * flow is tiny but the no-resurrection rule (terminal stays terminal)
 * is load-bearing: a future refactor that lets DECLINED transitions
 * back to PROPOSED would silently break the partial unique index's
 * invariant (one open proposal per job).
 */
class TransferStatusTest {

    @Test
    void proposed_canMoveToAnyTerminal() {
        assertThat(TransferStatus.PROPOSED.allowedNextStates())
                .containsExactlyInAnyOrder(
                        TransferStatus.ACCEPTED,
                        TransferStatus.DECLINED,
                        TransferStatus.CANCELLED,
                        TransferStatus.EXPIRED);
    }

    @ParameterizedTest
    @EnumSource(value = TransferStatus.class, names = {"ACCEPTED", "DECLINED", "CANCELLED", "EXPIRED"})
    void terminalStates_haveNoOutgoingEdges_andAreTerminal(TransferStatus terminal) {
        assertThat(terminal.allowedNextStates()).isEmpty();
        assertThat(terminal.isTerminal()).isTrue();
    }

    @Test
    void canTransitionTo_returnsTrueForAllLegalMoves() {
        assertThat(TransferStatus.PROPOSED.canTransitionTo(TransferStatus.ACCEPTED)).isTrue();
        assertThat(TransferStatus.PROPOSED.canTransitionTo(TransferStatus.DECLINED)).isTrue();
        assertThat(TransferStatus.PROPOSED.canTransitionTo(TransferStatus.CANCELLED)).isTrue();
        assertThat(TransferStatus.PROPOSED.canTransitionTo(TransferStatus.EXPIRED)).isTrue();
    }

    @Test
    void canTransitionTo_returnsFalseForSelfLoop() {
        assertThat(TransferStatus.PROPOSED.canTransitionTo(TransferStatus.PROPOSED)).isFalse();
    }

    @Test
    void canTransitionTo_returnsFalseForTerminalToAnything() {
        for (TransferStatus terminal : new TransferStatus[]{
                TransferStatus.ACCEPTED, TransferStatus.DECLINED,
                TransferStatus.CANCELLED, TransferStatus.EXPIRED}) {
            for (TransferStatus target : TransferStatus.values()) {
                assertThat(terminal.canTransitionTo(target))
                        .as("terminal %s must not allow transition to %s", terminal, target)
                        .isFalse();
            }
        }
    }

    @Test
    void proposed_isNotTerminal() {
        assertThat(TransferStatus.PROPOSED.isTerminal()).isFalse();
    }

    @Test
    void allowedNextStates_isDefensivelyCopied() {
        var copy = TransferStatus.PROPOSED.allowedNextStates();
        copy.clear();
        assertThat(TransferStatus.PROPOSED.allowedNextStates())
                .as("mutating the returned set must not affect the matrix")
                .isNotEmpty();
    }
}
