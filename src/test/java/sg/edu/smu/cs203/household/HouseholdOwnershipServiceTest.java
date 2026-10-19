package sg.edu.smu.cs203.household;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import sg.edu.smu.cs203.security.AccountRole;
import sg.edu.smu.cs203.security.UserAccount;
import sg.edu.smu.cs203.security.UserAccountRepository;

class HouseholdOwnershipServiceTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final HouseholdOwnershipService service = new HouseholdOwnershipService(accounts);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void residentCanAccessOnlyTheirOwnHousehold() {
        authenticateAs("resident");
        when(accounts.findByUsername("resident")).thenReturn(Optional.of(
                new UserAccount("resident", "hash", AccountRole.RESIDENT, 7L, false)));

        assertThatCode(() -> service.requireAccess(7L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireAccess(8L))
                .isInstanceOf(HouseholdAccessDeniedException.class);
    }

    @Test
    void adminCanAccessAnyHousehold() {
        authenticateAs("admin");
        when(accounts.findByUsername("admin")).thenReturn(Optional.of(
                new UserAccount("admin", "hash", AccountRole.ADMIN, null, false)));

        assertThatCode(() -> service.requireAccess(8L)).doesNotThrowAnyException();
    }

    @Test
    void residentsWithoutARealHouseholdAreDenied() {
        authenticateAs("resident");
        when(accounts.findByUsername("resident")).thenReturn(Optional.of(
                new UserAccount("resident", "hash", AccountRole.RESIDENT, 7L, true)));

        assertThatThrownBy(() -> service.requireAccess(7L))
                .isInstanceOf(HouseholdAccessDeniedException.class);
    }

    @Test
    void anonymousAndUnknownAccountsAreDenied() {
        assertThatThrownBy(() -> service.requireAccess(7L))
                .isInstanceOf(HouseholdAccessDeniedException.class);

        authenticateAs("missing");
        when(accounts.findByUsername("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireAccess(7L))
                .isInstanceOf(HouseholdAccessDeniedException.class);
    }

    private static void authenticateAs(String username) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(username, "n/a", List.of()));
    }
}
