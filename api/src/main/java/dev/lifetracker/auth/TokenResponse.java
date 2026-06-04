package dev.lifetracker.auth;

public record TokenResponse(String token, String email, String displayName) {}
