package com.eventticket.identity.support;

import com.eventticket.shared.UserDirectory;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.eventticket.identity.domain.AppUser;
import com.eventticket.identity.repository.AppUserRepository;

@Component
public class AppUserDirectory implements UserDirectory {

    private final AppUserRepository users;

    public AppUserDirectory(AppUserRepository users) {
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
