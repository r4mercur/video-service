package com.bjarne.videoservice.moderation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A reason is mandatory (CLAUDE.md 12, DSA justification requirement for moderation actions) -
 * ends up in the audit log.
 */
public record ModerationActionRequest(@NotBlank @Size(max = 2000) String reason) {
}
