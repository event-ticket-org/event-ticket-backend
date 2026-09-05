package com.eventticket.identity;

import com.eventticket.shared.UserDirectory;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class AppUserDirectory implements UserDirectory {

    private final AppUserRepository users;

    AppUserDirectory(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public boolean isVerified(UUID userId) {
        return users.findById(userId).map(AppUser::emailVerified).orElse(false);
    }

    @Override
    public boolean isPlatformAdmin(UUID userId) {
        return users.findById(userId).map(AppUser::platformAdmin).orElse(false);
    }

    @Override
    public UUID idForInvite(String emailAddress) {
        return users.findByEmail(emailAddress)
                .orElseGet(() -> users.save(AppUser.invited(emailAddress)))
                .id();
    }

    @Override
    public String emailOf(UUID userId) {
        return users.findOrThrow(userId).email();
    }

    @Override
    public String displayNameOf(UUID userId) {
        return users.findOrThrow(userId).displayName();
    }
}
