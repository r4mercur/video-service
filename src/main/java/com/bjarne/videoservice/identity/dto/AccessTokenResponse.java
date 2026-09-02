package com.bjarne.videoservice.identity.dto;

public record AccessTokenResponse(String accessToken, long expiresInSeconds) {
}
