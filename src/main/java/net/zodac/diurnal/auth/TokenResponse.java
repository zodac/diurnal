package net.zodac.diurnal.auth;

public record TokenResponse(String token, String email, String displayName) {}
