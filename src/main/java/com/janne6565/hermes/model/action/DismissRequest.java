package com.janne6565.hermes.model.action;

import io.swagger.v3.oas.annotations.media.Schema;

/** Clears a high-priority item from the open list without touching the mail in Gmail. */
@Schema(description = "Dismiss (or undo the dismissal of) a high-priority message")
public record DismissRequest(@Schema(defaultValue = "true") Boolean dismissed) {

    public boolean resolved() {
        return dismissed == null || dismissed;
    }
}
