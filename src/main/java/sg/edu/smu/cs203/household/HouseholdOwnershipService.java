package sg.edu.smu.cs203.household;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import sg.edu.smu.cs203.security.AccountRole;
import sg.edu.smu.cs203.security.UserAccount;
import sg.edu.smu.cs203.security.UserAccountRepository;

@Service
public class HouseholdOwnershipService {

    private final UserAccountRepository accounts;

    public HouseholdOwnershipService(UserAccountRepository accounts) {
        this.accounts = accounts;
    }

    public void requireAccess(long householdId) {
        UserAccount account = currentAccount();
        if (account.role() == AccountRole.ADMIN) {
            return;
        }
        if (account.role() != AccountRole.RESIDENT || account.simulatedHousehold()
                || account.householdId() == null || account.householdId() != householdId) {
            throw new HouseholdAccessDeniedException();
        }
    }

    public UserAccount currentAccount() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new HouseholdAccessDeniedException();
        }

        return accounts.findByUsername(authentication.getName())
                .orElseThrow(HouseholdAccessDeniedException::new);
    }
}
