# Playwright E2E

The suite reuses an already running console, API and OIDC service. It never starts
those services through Playwright. Service or OIDC direct-grant unavailability is
reported as a skipped test before Chromium is launched.

Defaults match the local Docker/demo setup. Override them when required:

```text
E2E_WEB_URL=http://localhost:5173
E2E_API_URL=http://localhost:8080
E2E_OIDC_URL=http://localhost:8180
E2E_OIDC_REALM=infinity-knowledge
E2E_OIDC_DIRECT_CLIENT_ID=infinity-knowledge-cli
E2E_ADMIN_USERNAME=demo-admin
E2E_ADMIN_PASSWORD=demo-admin
E2E_READER_USERNAME=demo-reader
E2E_READER_PASSWORD=demo-reader
```

List tests without starting services or launching a browser with `npm run e2e:list`.
Running `npm run e2e` requires a Chromium binary installed separately from this repo.
To reuse a locally installed Chrome instead of downloading Playwright's bundled Chromium,
set the optional browser channel first:

```powershell
$env:E2E_BROWSER_CHANNEL = 'chrome'
npm.cmd run e2e
```

The current suite contains eight serial scenarios: the governed Admin journey, immutable
Space creation, ACL-scoped Reader access, multi-file TEST_ONLY extraction, request-level
test configuration, exact Tokenizer selection, cancellation, and formal ingestion
identity protection. These scenarios create real Spaces, runs and source objects; run
them only in a dedicated acceptance environment.

The immutable Space scenario also verifies that the create request cannot submit an
implementation contract, the server-generated Pipeline/Normalizer/Parser/Cleaner/
Chunker-Tokenizer contract is complete, an idempotent duplicate `POST` keeps that contract
unchanged, the server reports whether the current deployment still matches it, and the
removed configuration `PUT` remains `405`. Run details must expose the
same processing contract plus the source normalizer used by that run, so Baseline and test
configuration comparisons include implementation drift rather than only IDs and parameters.

The 2026-08-11 controlled acceptance used this Chrome channel with one worker. Its
historical result predates the immutable Space-creation scenario, so use a fresh
`npm run e2e` result when accepting the current contract.
