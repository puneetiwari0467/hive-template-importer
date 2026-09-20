# HTTP API

The React client and Spring Boot server share the shapes in
`frontend/src/contracts.ts`. All JSON uses camelCase. All resource IDs are UUIDs.
Times are ISO 8601 UTC strings. The API never returns database credentials.

## Anonymous private workspaces

`POST /api/workspaces` creates a private demo workspace with an imported copy of
the bundled sample. It returns `{ token, workspaceId, templateId }` (201).
The token is a cryptographically random bearer credential returned only here.
The database stores its hash, not the raw token. Keep the token in browser local
storage so closing and reopening the app retrieves the same server-side records.
Templates and their contents are never persisted in browser storage.

All template endpoints require `Authorization: Bearer <token>` and scope every
read and write to that workspace. There is no sign-up, email, password recovery,
or third-party login. A different browser gets a separate seeded workspace.

## Routes

| Method | Route | Request | Response |
| --- | --- | --- | --- |
| GET | `/api/health` | None | `{ "status": "UP" }` when the database is reachable |
| GET | `/api/sample` | None | The exact bundled Spectora spreadsheet |
| POST | `/api/workspaces` | None | `WorkspaceCreated` (201) |
| GET | `/api/templates` | Bearer token | `{ "templates": TemplateSummary[] }` |
| GET | `/api/templates/{id}` | Bearer token | `TemplateDetail` |
| POST | `/api/templates/import` | Multipart `file`, optional `name` | `TemplateDetail` (201) |
| PUT | `/api/templates/{id}` | `TemplateUpdate` | Updated `TemplateDetail` |
| POST | `/api/templates/{id}/duplicate` | `{ "name": "Independent copy" }` | New `TemplateDetail` (201) |
| DELETE | `/api/templates/{id}` | None | No content (204) |

The update payload contains the entire existing hierarchy's editable fields.
It cannot add, remove, or move records. IDs must belong to the same template and
retain their parents. The version is checked transactionally; stale versions
return 409 rather than overwriting another tab's edits.

Deleting a template is workspace-scoped and cascades to its sections, items and
comments. Independent copies are not deleted; their `duplicateOf` reference
becomes null while their copied content remains intact.

`contentHtml` is the exact editable source text or HTML; `originalHtml` retains
the original imported field. `previewHtml` is an independently sanitized,
display-only projection. `metadata` retains the original export fields keyed
by their original headers, including values the editor does not interpret.
Import warnings explain unsupported display features and preserved-only fields.
Source rows are spreadsheet row numbers, including the header.

## Errors and limits

Errors use `{ "code": "...", "message": "...", "details": [] }` with an appropriate
HTTP status: 400 invalid edits, 401 invalid workspace credential, 404 resource
not in this workspace, 409 edit conflict, 413 upload too large, 422 unsupported
or invalid export, 429 demo limit, and 500 unexpected server failure.

Uploads are limited to 10 MiB. The parser has independent row, cell, worksheet
and archive limits; it rejects unsupported input rather than returning a
success-shaped partial import. Formula cells are not evaluated.

The public demo must not be used with real customer data. Clearing the workspace
token does not delete database records but removes this browser's access to
them. This deliberate demo limitation is not a production identity system.
