package sg.edu.smu.cs203.security;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import sg.edu.smu.cs203.household.HouseholdOwnershipService;

@RestController
public class CurrentSessionController {

    private final HouseholdOwnershipService ownership;

    public CurrentSessionController(HouseholdOwnershipService ownership) {
        this.ownership = ownership;
    }

    @GetMapping("/api/v1/session")
    public SessionResponse currentSession() {
        UserAccount account = ownership.currentAccount();
        return new SessionResponse(account.role());
    }

    public record SessionResponse(AccountRole role) {
    }
}
