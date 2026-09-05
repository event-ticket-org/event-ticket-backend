package com.eventticket.identity;

import com.eventticket.api.AuthApi;
import com.eventticket.api.model.LoginRequest;
import com.eventticket.api.model.Me;
import com.eventticket.api.model.RefreshRequest;
import com.eventticket.api.model.RegisterRequest;
import com.eventticket.api.model.SwitchOrganizationRequest;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.VerifyEmailRequest;
import com.eventticket.organization.Membership;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Implements the generated {@link AuthApi}, so a change to the contract that this class does
 * not follow is a compile error rather than a runtime surprise.
 *
 * <p>Generated types stop here. Use cases take and return domain types, and this class maps
 * between the two - the rule from ADR-0001 that keeps the published contract from quietly
 * becoming the domain model.
 */
@RestController
class AuthController implements AuthApi {

    private final RegisterUser registerUser;
    private final VerifyEmail verifyEmail;
    private final Login login;
    private final RefreshSession refreshSession;
    private final Logout logout;
    private final SwitchOrganization switchOrganization;
    private final GetMe getMe;

    AuthController(RegisterUser registerUser, VerifyEmail verifyEmail, Login login,
                   RefreshSession refreshSession, Logout logout,
                   SwitchOrganization switchOrganization, GetMe getMe) {
        this.registerUser = registerUser;
        this.verifyEmail = verifyEmail;
        this.login = login;
        this.refreshSession = refreshSession;
        this.logout = logout;
        this.switchOrganization = switchOrganization;
        this.getMe = getMe;
    }

    @Override
    public ResponseEntity<Void> authRegisterPost(RegisterRequest request) {
        registerUser.register(request.getEmail(), request.getPassword(), request.getDisplayName());
        // 202: the account exists but is not usable until the emailed link is followed.
        return ResponseEntity.accepted().build();
    }

    @Override
    public ResponseEntity<TokenPair> authVerifyEmailPost(VerifyEmailRequest request) {
        return ResponseEntity.ok(tokenPair(verifyEmail.verify(request.getToken())));
    }

    @Override
    public ResponseEntity<TokenPair> authLoginPost(LoginRequest request) {
        return ResponseEntity.ok(tokenPair(login.login(request.getEmail(), request.getPassword())));
    }

    @Override
    public ResponseEntity<TokenPair> authRefreshPost(RefreshRequest request) {
        return ResponseEntity.ok(tokenPair(refreshSession.refresh(request.getRefreshToken())));
    }

    @Override
    public ResponseEntity<Void> authLogoutPost() {
        logout.logout();
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<TokenPair> authSwitchOrganizationPost(SwitchOrganizationRequest request) {
        return ResponseEntity.ok(tokenPair(switchOrganization.switchTo(request.getOrganizationId())));
    }

    @Override
    public ResponseEntity<Me> meGet() {
        MeView view = getMe.get();
        AppUser user = view.user();

        Me me = new Me(user.id(), user.email(), user.displayName(), user.emailVerified(),
                view.memberships().stream().map(m -> membership(m, view.organizationNames().get(m.organizationId()))).toList());
        me.setPlatformAdmin(user.platformAdmin());
        return ResponseEntity.status(HttpStatus.OK).body(me);
    }

    private static com.eventticket.api.model.Membership membership(Membership m, String organizationName) {
        var dto = new com.eventticket.api.model.Membership(
                m.userId(), m.organizationId(), com.eventticket.api.model.Role.fromValue(m.role().name()));
        dto.setOrganizationName(organizationName);
        return dto;
    }

    private static TokenPair tokenPair(Session session) {
        TokenPair pair = new TokenPair(session.accessToken(), session.refreshToken(),
                (int) session.expiresInSeconds());
        pair.setActiveOrganizationId(session.activeOrganizationId());
        return pair;
    }
}
