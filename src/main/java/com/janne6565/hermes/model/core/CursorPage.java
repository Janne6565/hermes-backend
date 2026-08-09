package com.janne6565.hermes.model.core;

import java.util.List;

/**
 * One page of incremental sync: what arrived, and where to resume from.
 *
 * <p>Gmail puts a {@code historyId} in {@code cursor}; Microsoft Graph puts a
 * {@code @odata.deltaLink}. The sync loop does not care which — it stores the string and hands it
 * back next time.
 */
public record CursorPage(List<String> addedIds, String cursor) {}
