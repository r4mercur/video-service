package com.bjarne.videoservice.moderation.entity;

/**
 * Fixed taxonomy instead of free text - better triage for admins when reviewing reports (AP7).
 */
public enum ReportReason {
    COPYRIGHT,
    ILLEGAL_CONTENT,
    HARASSMENT,
    SPAM,
    OTHER
}
