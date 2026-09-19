# Implementation notes

## Customer and scope

The customer is an inspection company migrating years of reusable template
wording from Spectora. The deliverable is a structured template importer/editor,
not a tool for conducting an inspection or publishing property reports.

The baseline covers upload, faithful mapping, editing and saving, independent
duplication, real MySQL persistence and a hosted web interface. The extra
customer-focused improvement is an import verification/source-audit experience:
an inspector can see counts, warnings, original text and preserved source fields
rather than being asked to trust a generic "success" toast.

## Source material

- Template: Room-by-Room Residential Template.
- Source: Puneet's Spectora account, exported using Export to spreadsheet ->
  Export HTML Text.
- Original file: `sample-data/Room-by-Room Residential Template-2026-09-18.xls`.
- SHA-256: `f0df035e04f688485080a16259486fdcbb1c1e06393281305df0634675cca406`.
- 42 columns, 798 comment records, 22 distinct section names and 136 distinct
  section/item pairs.
- Comment types in this sample: 114 information, 23 limitations, 661 defects.
- Apache POI's decoded cell inspection identifies 365 comment text fields with
  HTML (208 distinct bodies). This supersedes an earlier raw-XML counting
  assumption; preservation tests compare the actual spreadsheet cell values.
  Empty comment text fields are legitimate,
  for example when the source comment defines a multiple-choice question.

The filename says `.xls`, but its actual format is OOXML/XLSX. Selecting a parser
solely from the extension would reject a valid customer export.

## Engineering decisions

- **Java 21 / Spring Boot / JDBC / MySQL.** Relational tables make hierarchy,
  ownership, ordering and deep-copy independence explicit. Flyway makes schema
  initialization repeatable. JSON is limited to source metadata and import
  reports; the complete template is not stored as an opaque HTML/JSON document.
- **Deterministic parsing, not an LLM.** The export has named columns, so
  explicit mapping is easier to verify and avoids model-invented wording.
- **Original text and editable text are separate.** Rendering a safe preview
  must not destroy the original field. Source information remains available
  even when it cannot be reproduced as an interactive inspection feature.
- **Atomic writes.** Import, save and duplication succeed as a whole or fail.
  Editing is versioned to prevent accidental last-writer-wins overwrites.
- **Isolated anonymous demo workspaces.** A random bearer credential identifies
  a browser's server-side workspace. Only its hash is stored server-side.
  This avoids a signup flow while preventing one visitor from editing another
  visitor's templates. It is deliberately not a production identity system.
- **Bounded work.** Upload, workbook and demo limits keep untrusted or
  unexpectedly large inputs from exhausting the service.

## Intentionally out of scope

- Actual inspection completion, report generation, payments, scheduling,
  homeowner portals and mobile apps.
- A complete form engine executing Spectora answer types, default values,
  estimates, recommendations or photo behavior. Source settings are preserved
  as reference metadata and clearly identified as such.
- Importing completed inspection reports or arbitrary spreadsheets.
- A full editor for adding/removing/reordering structural records.
- Organizations, role-based permissions, account recovery and collaboration.
- AI-based content generation or automatic rewriting.

These cuts protect the two-day focus: preserve the customer's template and make
the basic migration/edit/copy workflow dependable.

## Honest limits

- Sections and items retain first appearance in the sheet. Comments use numeric
  `Order (w/i item)` ascending, with source-row order breaking ties. If any
  comment in an item has a missing or invalid order, the entire item's comments
  retain sheet order with a warning. Original order values and source rows remain
  available in metadata; UI/database positions are zero-based.
- Names are kept as the decoded Excel cell values, including literal entity
  strings such as `&amp;` where the export contains them. They are not guessed
  into different stored names. The user can rename them; the source audit keeps
  the original values.
- A spreadsheet cannot tell us about source-system information that it does not
  contain. An empty photo field is not evidence that the original product had
  no photos. Missing export information and unsupported importer behavior must
  not be conflated.
- Retaining a field as metadata is not the same as implementing its inspection
  behavior. The source-audit panel and warnings make this distinction visible.
- External media are not downloaded or mirrored. Unsafe or unsupported HTML is
  not executed in previews; the original field is retained.
- The editor deliberately uses raw text/HTML with a safe formatted preview, not
  a rich-text editor that might rewrite untouched markup. Frontend previews
  suppress source styles, images, embeds and active links. Link wording remains
  visible; the original URL is available in the original HTML/source audit.
- Same-named hierarchy entries without source identifiers may be ambiguous.
  The parser's documented grouping/order policy should be considered when
  comparing exports with intentionally duplicated section or item names.
- Browser storage contains the access token only. Data survives app/server
  restarts, but losing the token loses access to that private workspace. There
  is no recovery workflow or production retention policy.
- This publicly reachable demo must only receive sample material you can share.
- Workbook limits: 10 MiB upload; 5,000 rows including the header; 256 columns;
  200,000 physical cells; up to 16 sheets with exactly one nonempty data sheet;
  32,767 characters per cell. Archive/XML/BIFF preflight and aggregate character
  budgets run before full parsing, with at most two simultaneous parses.
  Unsupported formulas, error cells, macros or unsafe archive structures fail
  explicitly. See `backend/.env.example` for the complete bounds.
- Demo limits: 500 workspaces, 10 templates and 25,000 comments per workspace.
  Creation/import rate guards use the direct socket IP, not untrusted forwarded
  headers; users behind a shared proxy may share that rate allowance.

## Hosting and cost decisions

The complete demo is hosted on Azure, with React's built output served by the
same Java process as the API. Spring Boot
cannot run as a normal long-lived process on Vercel, so the submission explicitly
uses the assignment's alternative-host provision. The React frontend remains
independently deployable to Vercel.

The API has a separate Linux B2 App Service plan. ATS Radar remains on its
existing Functions Consumption plan with no resource, configuration or restart
changes. The database uses a separate managed MySQL 8.4 B1ms server in South India.
Azure refused new MySQL provisioning in Central India for this subscription,
so the database region was changed explicitly; the API remains in Central India.
This is demo sizing, not a promise of sustained production throughput.

Initial Central India list-price estimate at 730 hours/month: B2 $26.28;
MySQL B1ms $17.89 plus 20 GiB storage $2.62. These exclude extra I/O, backup,
networking and monitoring. Region, actual hours and usage affect billing.
The App Service plan continues billing while allocated even if the app is stopped.

Live URL: https://hive-pt-5d3781.azurewebsites.net/

Login: none. Each browser gets an independent, imported demo workspace.

During a 20-minute window containing real smoke/browser checks and a restart,
Azure reported B2 CPU averaging 10.45%, with a maximum one-minute average of
52%; memory averaged 58.5%, with a maximum one-minute average of 67%. These are
observed demo measurements, not an instantaneous peak or a production load-test
guarantee. ATS Radar's existing Functions app remained running on its original
Y1 plan and was not changed or restarted.

## Verification record

- Frontend: 43 tests passed across API/error handling, safe previews, draft
  serialization, workspace persistence, import/copy UI and conflict handling.
  These use explicit test-only API mocks, not a database substitute.
- Frontend TypeScript check and Vite production build passed.
- A full npm dependency audit reported zero known vulnerabilities after updating
  Vitest to patched 4.1.11. The production bundle was unchanged by that
  development-tool update.
- Backend: **74 tests passed with zero failures, errors or skips**, including
  12 real-MySQL integration tests. The local isolated server was MySQL 8.0.46;
  it was not an H2 substitute and did not reuse an existing application database.
- Parser tests compare every decoded cell value with the preserved metadata,
  verify names/content/hierarchy/order against the real source, and exercise
  other generated BIFF/OOXML fixtures, reordered headers, unsupported fields,
  formula/error cells, archive/XML limits and visible failures.
- The complete packaged JAR includes byte-exact copies of all four React
  production assets and the original Spectora workbook.
- The eight-check HTTP smoke suite passed locally and against the **live
  Azure/MySQL 8.4** application: DB health, seeded workspace, real import and
  source comparison, edit/reload/safe preview, stale-save conflict, independent
  deep copy, invalid-file atomicity, and workspace isolation.
- Live browser checks imported the actual file, renamed a section and item,
  edited/saved HTML, reloaded those edits, changed a copy without changing its
  original, and displayed `INVALID_EXPORT / HTTP 422` for the supplied invalid
  fixture without adding a partial template.
- The importer App Service was deliberately restarted. The same browser token
  still retrieved all three test templates and the previously saved section,
  item and comment edits; the original remained independent of its copy.
- Azure MySQL 8.4 is provisioned in South India. Database bootstrap succeeded
  with hostname/certificate verification and the application has a schema-scoped
  account. The temporary operator-IP firewall rule was removed.

GitHub Actions was omitted at Puneet's request to avoid further authorization
delay. `docs/ci-workflow.example.yml` is an inactive, optional example, not a
claim that cloud CI ran. The executed checks above and the reproducible test
commands are the verification evidence.

## Product exploration and recording

Puneet has used Hive and produced a sample inspection report, and obtained the
Spectora template used here. The Hive report is product research, not importer
input. The walkthrough must include Puneet's own specific observations from
trying Hive's template-import workflow. No Binsr comparison is claimed without
actually exploring it.

The video must be recorded in Puneet's own voice, with a camera introduction.
See `docs/WALKTHROUGH.md` for the 8-10 minute outline.

## Readability follow-up

- Removed the oversized title/statistics area, duplicate navigation labels,
  repeated duplicate action, marketing text, and persistent explanatory panels.
  Import details and warnings remain accessible in Import review and Source audit;
  workspace/privacy information is available in a compact disclosure.
- Full template names wrap instead of being line-clamped. Desktop template and
  section rails are wider, with larger labels and comment editing/preview text.
- Narrow windows use native template/section selectors instead of squeezing long
  lists into short scrolling boxes. Section navigation retains unsaved drafts,
  and template switching retains the same discard confirmation.
- Browser measurements passed at 1440x900, 1280x720, 1024x768, 503x350 and 390x844:
  no horizontal page overflow, at least 75% desktop height for the editing
  workspace, readable text, and natural document scrolling in short/narrow panes.
- A desktop viewport override left on the integrated browser during testing was
  cleared. Responsive checks now use a separate test page, not the user's live
  editor.
- Import validation was not weakened: the real Spectora sample still imports;
  the intentionally invalid text fixture still returns HTTP 422. The upload
  dialog now explains this distinction before submission.

## Tools and contributions

Copilot was used for implementation, test generation, debugging and deployment
assistance. The project retains the API contract, source fixture, tests,
provisioning script, build script and HTTP smoke script so the decisions and
verification can be reproduced.

Framework/library credit: Spring Boot, Apache Maven Wrapper, Apache POI, MySQL,
Flyway, jsoup, React, TypeScript, Vite and the frontend libraries declared in its
package manifest. The original inspection content is credited to its source;
no ownership of the exported wording is claimed.

Implementation began on 19 September 2026 at approximately 12:28 IST. Elapsed
session time was approximately five hours, including Azure provisioning,
authentication/user waits, parallel AI-assisted implementation and verification.
This is elapsed time, not five hours of Puneet's manual coding.
