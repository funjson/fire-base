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

The 2026-08-11 controlled acceptance used this Chrome channel with one worker and passed
both serial Admin and Reader scenarios.
