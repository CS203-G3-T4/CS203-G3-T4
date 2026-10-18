package sg.edu.smu.cs203.forecast;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.assertThat;

class ForecastAdminAuthenticationTest {
    private final AtomicReference<HttpServletRequest> downstream=new AtomicReference<>();

    private MockHttpServletResponse filter(boolean enabled,MockHttpServletRequest request,String password) throws Exception {
        var response=new MockHttpServletResponse();
        new ForecastAdminAuthentication(enabled,"test-admin",password).doFilter(request,response,
                (req,res)->downstream.set((HttpServletRequest)req));
        return response;
    }
    private MockHttpServletRequest request(String path,String credentials) {
        var request=new MockHttpServletRequest("GET",path);
        if (credentials!=null) request.addHeader("Authorization","Basic "+Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        return request;
    }
    @Test void credentialsAloneNeverEnableLocalAuthentication() throws Exception {
        var response=filter(false,request("/admin-forecast.html","test-admin:test-password"),"test-password");
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isNull();
        assertThat(downstream.get()).isNull();
    }
    @Test void missingWrongAndMalformedCredentialsAreDenied() throws Exception {
        for (String value:new String[]{null,"test-admin:wrong","invalid"}) {
            var response=filter(true,request("/api/v1/admin/forecast-accuracy",value),"test-password");
            assertThat(response.getStatus()).isEqualTo(403);
            assertThat(downstream.get()).isNull();
        }
        var malformed=request("/admin-forecast.html",null);
        malformed.addHeader("Authorization","Basic !!!");
        assertThat(filter(true,malformed,"test-password").getStatus()).isEqualTo(401);
        assertThat(filter(true,request("/api/v1/admin/forecast-accuracy","test-admin:"),"").getStatus()).isEqualTo(403);
    }
    @Test void explicitLocalCredentialsAuthorizeOnlyForecasting() throws Exception {
        assertThat(filter(true,request("/api/v1/admin/forecast-accuracy","test-admin:test-password"),"test-password").getStatus()).isEqualTo(200);
        assertThat(downstream.get().isUserInRole("ADMIN")).isTrue();
        assertThat(downstream.get().getUserPrincipal().getName()).isEqualTo("test-admin");
    }
    @Test void unrelatedAdminRoutesPassThroughWithoutAnInjectedPrincipal() throws Exception {
        for (String path:new String[]{"/api/v1/admin/users","/api/v1/admin/forecast-unknown","/admin.html"}) {
            filter(true,request(path,"test-admin:test-password"),"test-password");
            assertThat(downstream.get().getUserPrincipal()).isNull();
            assertThat(downstream.get().isUserInRole("ADMIN")).isFalse();
        }
    }
    @Test void sharedAdminWorksWithLocalAuthenticationDisabled() throws Exception {
        var request=request("/admin-forecast.html",null);
        request.setUserPrincipal(()->"shared-admin"); request.addUserRole("ADMIN");
        assertThat(filter(false,request,"").getStatus()).isEqualTo(200);
        assertThat(downstream.get().getUserPrincipal().getName()).isEqualTo("shared-admin");
    }
    @Test void sharedResidentCannotBeElevatedEvenWithValidLocalCredentials() throws Exception {
        var request=request("/admin-forecast.html","test-admin:test-password");
        request.setUserPrincipal(()->"resident"); request.addUserRole("RESIDENT");
        assertThat(filter(true,request,"test-password").getStatus()).isEqualTo(403);
        assertThat(downstream.get()).isNull();
    }
    @Test void crossOriginWritesRemainDenied() throws Exception {
        var request=request("/api/v1/admin/forecast-demand","test-admin:test-password");
        request.setMethod("POST"); request.addHeader("Origin","https://unrelated.example");
        assertThat(filter(true,request,"test-password").getStatus()).isEqualTo(403);
        assertThat(downstream.get()).isNull();
    }
}
