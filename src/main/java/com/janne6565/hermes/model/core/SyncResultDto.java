package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What one pass over the connected mailboxes did.
 *
 * <p>{@code failed} is reported separately from {@code ingested} because the two zeroes mean
 * opposite things: nothing new is the good outcome, nothing new because every account threw is the
 * one the UI has to say out loud rather than render as a quiet "up to date".
 */
@Schema(description = "Outcome of a mail sync run")
public record SyncResultDto(
        @Schema(
                        description =
                                "A sync was already in progress; nothing was started and the"
                                        + " counts below are not a result")
                boolean alreadyRunning,
        @Schema(example = "1", description = "Connected mailboxes visited") int accounts,
        @Schema(example = "3", description = "Previously-unseen messages classified and stored")
                int ingested,
        @Schema(example = "0", description = "Mailboxes whose sync threw; see /api/v1/health")
                int failed) {}
