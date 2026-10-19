package sg.edu.smu.cs203.security;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class AccountUserDetailsService implements UserDetailsService {

    private final UserAccountRepository accounts;

    public AccountUserDetailsService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        UserAccount account = accounts.findByUsername(username)
                .filter(user -> user.role() != AccountRole.RESIDENT || !user.simulatedHousehold())
                .orElseThrow(() -> new UsernameNotFoundException("Invalid username or password"));
        return new AccountUserDetails(
                account.username(), account.passwordHash(), account.role(), account.householdId());
    }
}
