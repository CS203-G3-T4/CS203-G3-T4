package sg.edu.smu.cs203.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Admin overview")
@RestController
public class AdminOverviewController {

    private final AdminOverviewService service;

    public AdminOverviewController(AdminOverviewService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/admin/overview")
    @Operation(summary = "Platform-wide household, savings and forecast accuracy overview (CSDT4-55)")
    public AdminOverviewResponse overview() {
        return service.overview();
    }

    @GetMapping("/api/v1/admin/households/summary")
    @Operation(summary = "Per-household plan, monthly savings and budget status (CSDT4-55)")
    public List<AdminOverviewResponse.HouseholdSummary> households() {
        return service.overview().households();
    }
}
