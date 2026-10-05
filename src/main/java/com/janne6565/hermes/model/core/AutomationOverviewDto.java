package com.janne6565.hermes.model.core;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "The automations screen in one read")
public record AutomationOverviewDto(
        List<AutomationDto> automations,
        @Schema(description = "Most recent firings first, test runs included")
                List<AutomationRunDto> recentRuns,
        @Schema(description = "Enabled automations allowed at once — each one is prompt text")
                int limit) {}
