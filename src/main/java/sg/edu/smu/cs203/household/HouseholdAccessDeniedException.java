package sg.edu.smu.cs203.household;

public class HouseholdAccessDeniedException extends RuntimeException {

    public HouseholdAccessDeniedException() {
        super("You do not have access to this household.");
    }
}
