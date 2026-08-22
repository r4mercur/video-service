package com.bjarne.videoservice.identity;

public record AccessTokenResponse(String accessToken, long expiresInSeconds) {
}
