package com.bjarne.videoservice.moderation;

/**
 * Feste Taxonomie statt Freitext - bessere Triage fuer Admins bei der Report-Pruefung (AP7).
 */
public enum ReportReason {
    COPYRIGHT,
    ILLEGAL_CONTENT,
    HARASSMENT,
    SPAM,
    OTHER
}
