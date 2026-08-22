package com.bjarne.videoservice.moderation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Begruendung ist Pflicht (CLAUDE.md 12, DSA-Begruendungspflicht bei Moderationsmassnahmen) -
 * landet im Audit-Log.
 */
public record ModerationActionRequest(@NotBlank @Size(max = 2000) String reason) {
}
