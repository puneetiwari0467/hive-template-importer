# Template Studio

A Spectora template importer built for the Hive Inspect take-home assignment.
Move an inspector's reusable sections, items and comments into an editable
workspace without silently discarding their source material.

**React + TypeScript / Spring Boot + Java 21 / MySQL**

**Live demo:** https://hive-pt-5d3781.azurewebsites.net/

**Repository:** https://github.com/puneetiwari0467/hive-template-importer

**No login is required.** A new browser receives its own imported sample
workspace. Its changes do not affect other reviewers. Use the bundled sample,
not real customer data.

## What it does

- Imports a Spectora **Export to spreadsheet -> Export HTML Text** workbook.
- Preserves the original comment text, source fields and source locations.
- Shows the template hierarchy and an import verification report.
- Edits section names, item names, comment names and comment text/HTML.
- Persists edits in MySQL and detects conflicting saves from another tab.
- Creates a completely independent copy of a template.
- Deletes unwanted templates after explicit confirmation; independent copies remain.
- Gives each browser an isolated, pre-seeded demo workspace without requiring
  an account or exposing another reviewer's templates.
- Prioritizes the editing canvas: compact toolbar, readable full template names,
  larger section navigation, and compact selectors in narrow windows.

This is a **template-management application**, not an inspection report writer.
Use shareable sample data only.

### Which file should I import?

For a successful demo, use the actual workbook in `sample-data/`, or click
**Download sample workbook** in the app. The file
`test-data/not-a-workbook.xls` is intentionally plain text, despite its extension.
It is only for demonstrating a rejected upload in the walkthrough, not a valid
template. Rejecting it is the expected validation behavior.

## Repository

```text
frontend/       React UI, typed API contract and frontend tests
backend/        Spring Boot API, parser, Flyway migrations and Java tests
sample-data/    Original Spectora workbook, unchanged
scripts/        Azure provisioning and HTTP smoke verification
docs/           API reference and walkthrough guide
test-data/      Harmless invalid-file fixture for demonstrating failure handling
NOTES.md        Scope, limits, validation and implementation decisions
```

## Run locally

Prerequisites: Java 21 JDK, Node 24 LTS (or a Vite-compatible Node LTS), and a
running MySQL 8 server. Maven is downloaded by the included Maven Wrapper.
The optional PowerShell build/provisioning scripts require PowerShell 7.
Ensure `JAVA_HOME` also points to Java 21: Maven honors it even if `java -version`
on your PATH happens to show a different installation.

### 1. Create dedicated databases

Connect to your **local development** MySQL server as a database administrator.
Use a new password and do not reuse production credentials.

```sql
CREATE DATABASE hive_importer CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE hive_importer_test CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE USER 'hive_app'@'localhost' IDENTIFIED BY 'replace-with-your-local-password';
GRANT ALL PRIVILEGES ON hive_importer.* TO 'hive_app'@'localhost';
GRANT ALL PRIVILEGES ON hive_importer_test.* TO 'hive_app'@'localhost';
```

The runtime user needs schema migration privileges because Flyway initializes
these dedicated schemas. Never point integration tests at a production database.

### 2. Start the backend

In PowerShell:

```powershell
Set-Location backend
$env:DB_URL = "jdbc:mysql://127.0.0.1:3306/hive_importer?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:DB_USERNAME = "hive_app"
$env:DB_PASSWORD = "your-local-password"
$env:APP_ALLOWED_ORIGINS = "http://localhost:5173"
.\mvnw.cmd spring-boot:run
```

The disabled TLS setting above is **for loopback development only**. Production
uses `sslMode=VERIFY_IDENTITY` with the managed server's certificate chain.
Spring Boot reads environment variables; it does not automatically load a
`.env` file.

Flyway creates the tables. The first browser visit creates a private workspace
and imports the bundled sample into that workspace in the real database.

### 3. Start the frontend

In a second terminal:

```powershell
Set-Location frontend
npm ci
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api` to `http://localhost:8080`.
If you use a separate API host, set `VITE_API_BASE_URL` before building the UI.
No database credentials belong in a `VITE_` variable.

### Workspace persistence

Only a random workspace access token is kept in browser local storage. Templates,
edits, copies and import reports are stored in MySQL. Reopening the same browser
retrieves the same data. A new browser receives a different sample workspace.
Clearing the access token loses that browser's access; there is deliberately no
account recovery system in this take-home demo.

## Verification

Backend parser/unit tests:

```powershell
Set-Location backend
.\mvnw.cmd --batch-mode test
```

For the real-MySQL integration suite, also supply a **dedicated test database**:

```powershell
$env:TEST_DB_URL = "jdbc:mysql://127.0.0.1:3306/hive_importer_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
$env:TEST_DB_USERNAME = "hive_app"
$env:TEST_DB_PASSWORD = "your-local-password"
.\mvnw.cmd --batch-mode test
```

Without the test-database variables, database integration tests are explicitly
skipped, not replaced with an in-memory database.

Frontend:

```powershell
Set-Location frontend
npm test -- --run
npm run build
```

Against a running API, from the repository root:

```powershell
$env:API_BASE_URL = "http://localhost:8080"
node scripts\api-smoke.mjs
```

The smoke test creates isolated sample workspaces, imports the real workbook,
compares content/hierarchy/metadata, saves and reloads edits, tests stale saves,
deep copies, invalid files and cross-workspace access. It leaves its sample
records in its own workspaces; it never changes another visitor's workspace.

### Executed checks

- **74 backend tests passed, zero skips**, including real-MySQL integration.
- **43 frontend tests passed**, plus TypeScript and the production build.
- The HTTP smoke suite passed against both local MySQL and the **live Azure
  deployment with MySQL 8.4**.
- Live browser checks verified upload, edit/save/reload, independent duplication,
  and visible rejection of `test-data/not-a-workbook.xls`.
- Saved templates, edits, copies and workspace access survived an Azure
  application restart.
- `npm audit` reported zero vulnerabilities after updating the test runner.
- Layout checks cover desktop, tablet and small embedded-browser sizes.
  At 1440 x 900, the editing workspace has 759 px of height (about 84% of the
  viewport), with full-name navigation and 14-15 px editing text.

GitHub Actions is not enabled. The optional
[workflow example](docs/ci-workflow.example.yml) is included for future use;
the verification above was actually executed, not inferred from a workflow file.

## Deployment

The deployed demo uses Azure for the complete application. React's production
build is packaged as Spring Boot static resources, so the UI and API share one
origin. The database is a separate Azure resource:

- **Azure App Service Linux B2:** 2 cores, 3.5 GB RAM, Java 21, Always On.
- **Azure Database for MySQL Flexible Server:** B1ms, small dedicated database,
  MySQL 8.4, 20 GiB storage, no high availability for this demo, verified TLS and
  restricted firewall rules. The database is in South India; the app is in
  Central India because new MySQL provisioning there was unavailable.
- Existing ATS Radar infrastructure is not reused or modified.

The assignment prefers Vercel but permits another host for a stack that cannot
reasonably run there. The Java server requires a long-running host, so the
submission uses that alternative and identifies it explicitly. React can also
be deployed independently to Vercel using `frontend/` as the project root,
`npm run build` as the build command and `dist/` as the output directory; set
`VITE_API_BASE_URL` and the backend's allowed origins accordingly.

Build the frontend **before** packaging the backend:

```powershell
Set-Location frontend
npm ci
npm run build
Set-Location ..\backend
.\mvnw.cmd --batch-mode package
```

Provisioning is deliberately explicit and uses the signed-in Azure subscription:

```powershell
.\scripts\provision-azure.ps1 `
  -Prefix "your-unique-app-prefix" `
  -PrivateOutputDirectory "C:\private\hive-deployment"
```

This creates billable resources. It refuses to use an unrelated resource group.
Its random administrator credentials are saved **outside the repository** with
restricted permissions and are never printed. Application deployment must use
a dedicated database user scoped to this application's schema.
If Azure refuses MySQL provisioning in the selected region, choose a supported
region explicitly with `-DatabaseLocation`; the script does not silently choose
a more expensive tier or move an existing database.
After a failed region request, Azure can retain a name reservation even when
the server does not appear in the resource list. Use a distinct
`-DatabaseServerName` for an explicitly chosen new-region attempt.

Configure the application's schema-scoped database user using a trusted PEM
CA bundle (for example, the bundle distributed with Git for Windows):

```powershell
.\scripts\configure-azure.ps1 `
  -PrivateStatePath "C:\private\hive-deployment\azure-resources.json" `
  -CaBundle "C:\Program Files\Git\usr\ssl\certs\ca-bundle.crt"
```

This temporarily allows only the operator's IPv4 address for database bootstrap,
verifies the server's TLS identity, then removes that temporary firewall rule.
The application uses `hive_app`, not the server administrator. Application
settings are sent through a private file outside the repository and are never
printed.

Deploy the already built JAR, then verify the actual HTTP workflow:

```powershell
.\scripts\deploy-azure.ps1 -AppName "your-unique-app-prefix"
$env:API_BASE_URL = "https://your-app.azurewebsites.net"
node scripts\api-smoke.mjs
```

The B2 plan is billed while allocated, even if the web app is stopped. After
review, remove only this project's resource group if it is no longer required.
Do not remove or resize any ATS Radar resource.

See [API.md](docs/API.md), [NOTES.md](NOTES.md), and
[the walkthrough guide](docs/WALKTHROUGH.md).

The remaining submission artifact is the walkthrough recording, which Puneet
must record in his own voice with a camera introduction.

## Sample provenance and credits

The sample is Puneet's **Room-by-Room Residential Template** export from Spectora,
using **Export HTML Text**, downloaded as
`Room-by-Room Residential Template-2026-09-18.xls`. It is an OOXML workbook despite
the `.xls` extension; detection is based on file contents.

SHA-256:
`f0df035e04f688485080a16259486fdcbb1c1e06393281305df0634675cca406`

The source contains 42 columns and 798 comment rows across 22 distinct section
names and 136 section/item pairs. The file is kept unchanged for reproducible
review. The inspection vocabulary and source content belong to their respective
authors; no ownership of that material is claimed.

Built on Spring Boot, Apache POI, MySQL, Flyway, jsoup, React, TypeScript and Vite.
The Apache Maven Wrapper retains its original Apache license header. Copilot
assisted implementation and verification; the submitted parser, mapping,
workspace model and workflow are specific to this assignment. Further frontend
library credits and exact validation results are recorded in NOTES.md.
