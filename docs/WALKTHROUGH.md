# Walkthrough recording guide

Target 8-10 minutes, maximum 12. Use your own voice and turn your camera on for
the introduction. This is an outline, not a substitute for the required video.
Do not display private deployment files, tokens, passwords or Azure credentials.

Live app: https://hive-pt-5d3781.azurewebsites.net/

Repository: https://github.com/puneetiwari0467/hive-template-importer

No login is required. A fresh browser opens its own imported sample.

Use a full browser window when recording so the section navigation and editor
are visible side by side. Narrow embedded previews use template/section dropdowns.
Use the actual file in `sample-data/` for the successful import. The file in
`test-data/` is deliberately invalid and belongs only in the failure demonstration.

## 0:00-0:40 - Introduce yourself

Briefly explain your background and that this is a template migration tool.
The customer has years of inspection wording; preservation matters more than
inventing a new inspection system.

## 0:40-2:20 - Import the real file

1. Open the live app and point out the already imported sample.
2. Download the sample or select the committed Spectora workbook.
3. Import it with a recognizable name.
4. Show the verification summary, hierarchy, warnings and source audit.
5. Explain that unsupported behavior is called out, and source fields are retained.

## 2:20-3:40 - Edit, save and reopen

1. Rename a section and an item.
2. Edit one comment; show the formatted preview.
3. Save and reload the browser.
4. Point out that the saved data came back from MySQL, not browser storage.
5. Show the immutable original wording in the source audit.

## 3:40-4:40 - Independent copy

1. Duplicate the template and give it a different name.
2. Change a comment in the copy and save.
3. Return to the original and show that it has not changed.

## 4:40-6:10 - Repository and data model

- React/TypeScript frontend, Java/Spring Boot API, MySQL.
- Templates -> sections -> items -> comments, with positions and foreign keys.
- Separate original source metadata and display-only sanitized HTML.
- Atomic import/copy, optimistic version checking on edits.
- Actual sample fixture and the parser/real-MySQL tests.
- Explain the use of Copilot and which implementation choices you verified.

## 6:10-7:30 - Hard part and honest failure

- The file has a `.xls` name but contains an XLSX/OOXML workbook.
- Explain the actual ordering policy described in NOTES.md.
- Show a column retained as metadata rather than pretending it is a working
  inspection field.
- Upload the included `test-data/not-a-workbook.xls`; show the error and that
  no partial template appeared.
- If helpful, demonstrate a conflicting edit in two tabs returning an explicit
  conflict instead of overwriting.

## 7:30-8:40 - Decisions and limitations

- Focused on import fidelity and a usable editor, not report generation.
- The import verification view is the customer-focused improvement.
- Private browser workspaces protect each reviewer's sample edits; no full
  account system.
- Explain safe HTML rendering and the original-content audit.
- Mention the actual deployed hosting/database setup and scope limits.

## 8:40-9:30 - Hive product feedback

Use your own observations from trying Hive's template-import workflow.
State one concrete friction point, what you did, and what you would change.
Do not invent a Binsr comparison: explore it first or explain why you prioritized
the required Hive and Spectora exploration.

## Before sending

- Publish the video somewhere reviewers can open without requesting access.
- Check the live app from a fresh browser.
- Confirm reviewers can access the repository (public, or explicitly invited).
- Reply with repository URL, live URL/access instructions and video URL.
