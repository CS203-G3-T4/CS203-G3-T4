package sg.edu.smu.cs203.security;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfTokenController {

    @GetMapping("/api/v1/csrf")
    public ResponseEntity<CsrfTokenResponse> token(CsrfToken csrfToken) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken()));
    }

    public record CsrfTokenResponse(String headerName, String token) {
    }
}
