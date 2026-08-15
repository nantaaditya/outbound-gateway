package com.nantaaditya.example.model.dto;

import java.time.LocalDateTime;

/**
 * Aggregated dead-letter depth per process group — one row per
 * {@code (process_type, process_name)} pair with unprocessed rows.
 */
public record DlqDepthRow(
    String processType,
    String processName,
    long depth,
    LocalDateTime oldestCreatedDate) {
}
