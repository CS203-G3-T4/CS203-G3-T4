package sg.edu.smu.cs203.security;

public record UserAccount(
        String username,
        String passwordHash,
        AccountRole role,
        Long householdId,
        boolean simulatedHousehold) {
}
