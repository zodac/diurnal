package net.zodac.diurnal.user;

import java.util.UUID;

public record UserDto(UUID id, String email, String displayName) {

    public static UserDto from(User user) {
        return new UserDto(user.id, user.email, user.displayName);
    }
}
