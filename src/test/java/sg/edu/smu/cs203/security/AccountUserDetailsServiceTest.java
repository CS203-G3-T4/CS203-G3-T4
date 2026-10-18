package sg.edu.smu.cs203.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

class AccountUserDetailsServiceTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final AccountUserDetailsService service = new AccountUserDetailsService(accounts);
    private final PasswordEncoder passwordEncoder = new SecurityConfiguration().passwordEncoder();

    @Test
    void loadsTheStoredRoleAndPasswordHashAsAuthoritiesAndCredentials() {
        String passwordHash = passwordEncoder.encode("test-password");
        when(accounts.findByUsername("analyst")).thenReturn(Optional.of(
                new UserAccount("analyst", passwordHash, AccountRole.ADMIN, null, false)));

        var user = service.loadUserByUsername("analyst");

        assertEquals("analyst", user.getUsername());
        assertEquals(passwordHash, user.getPassword());
        assertTrue(passwordEncoder.matches("test-password", user.getPassword()));
        assertTrue(user.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    @Test
    void mapsResidentAccountsToTheResidentRole() {
        when(accounts.findByUsername("resident")).thenReturn(Optional.of(
                new UserAccount("resident", "hash", AccountRole.RESIDENT, 7L, false)));

        var user = service.loadUserByUsername("resident");

        assertTrue(user.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_RESIDENT")));
        assertEquals(7L, ((AccountUserDetails) user).householdId());
    }

    @Test
    void rejectsUnknownAccounts() {
        when(accounts.findByUsername("missing")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("missing"));
    }

    @Test
    void rejectsResidentAccountsForSimulatedHouseholds() {
        when(accounts.findByUsername("simulated")).thenReturn(Optional.of(
                new UserAccount("simulated", "$2a$hash", AccountRole.RESIDENT, 7L, true)));

        assertThrows(UsernameNotFoundException.class,
                () -> service.loadUserByUsername("simulated"));
    }
}
