# Forecast admin authentication: owner decision

The current main has no shared authentication implementation. The unmerged
`feature/CSDT4-23-login-resident-admin` branch has Spring Security session login,
ADMIN/RESIDENT roles and CSRF protection. This PR does not copy or replace it.
Its policy already protects `/api/v1/admin/**`; the forecast controller continues
requiring a servlet principal with the ADMIN role.

The retained servlet guard protects only these exact routes:

```
/admin-forecast.html
/api/v1/admin/forecast-accuracy
/api/v1/admin/forecast-baseline-validation
/api/v1/admin/forecast-demand
/api/v1/admin/forecast-training-attempts
/api/v1/admin/forecast-savings-replay
```

It does not intercept other admin routes or manufacture a principal there. It
runs after the shared security filter chain. Existing authenticated ADMIN users
pass; every authenticated non-admin is denied, even if a valid local Basic header
is also supplied. Controller/replay authorization remains server-side. The page
is protected as well as its API; responses are not cached.

## Local/demo only

Basic fallback is disabled by default, even if credentials happen to be set.
For an explicitly local/demo Spring process, set all three outside source:

```bash
export WATTLY_FORECAST_LOCAL_ADMIN_ENABLED=true
export WATTLY_ADMIN_USER=your-private-local-name
export WATTLY_ADMIN_PASSWORD=your-private-local-password
# Bind the demo to loopback; never commit this environment.
java -jar target/energy-market-service-0.0.1-SNAPSHOT.jar --server.address=127.0.0.1
```

The example strings are instructions, not configured/default credentials.
Missing/incorrect credentials deny access. Cross-origin writes are denied.
Disable by removing the flag or setting it `false`; clear the credentials too.
The flag must be configured on Spring, not merely on a scheduled Python process.
With no shared auth or local opt-in, admin APIs remain 403 and the page 401.

## Proposed integration for the auth owner

Use the shared session/role system for production; leave local Basic disabled.
Require ADMIN for the admin forecast page in the shared security configuration
as well as the already-covered admin APIs. Keep CSRF protection and adapt the
admin UI's read requests and operational client's authenticated POST mechanism
to that shared contract. A downstream servlet Basic wrapper cannot bypass the
shared chain's earlier denial, and must not be used to create a competing login.

The weekly Python client currently uses local Basic for its protected audit
POSTs. A production service-account/session/token and CSRF contract must be chosen
by the auth owner before enabling permanent scheduling with shared auth. No
CSRF exemptions or production credentials are added by this PR. Unit tests cover
shared-principal interoperability; an end-to-end merged-session login test must
run when the auth branch is integrated.

**Awaiting owner decision:** approve this forecasting-only temporary boundary,
confirm shared filter/page policy and choose the machine-client contract. No
owner acknowledgement is claimed. The implementation and local denial/role tests
are ready for that review.
