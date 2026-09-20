import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";

const base = (process.env.API_BASE_URL ?? "http://localhost:8080").replace(/\/$/, "");
const sampleUrl = new URL(
  "../sample-data/Room-by-Room Residential Template-2026-09-18.xls",
  import.meta.url,
);
const sample = await readFile(sampleUrl);
const expectedHash = createHash("sha256").update(sample).digest("hex");
const results = [];

async function request(path, { token, status = 200, ...options } = {}) {
  const headers = new Headers(options.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (typeof options.body === "string") headers.set("Content-Type", "application/json");
  const response = await fetch(`${base}/api${path}`, {
    ...options,
    headers,
    signal: AbortSignal.timeout(120_000),
  });
  const contentType = response.headers.get("content-type") ?? "";
  const body = contentType.includes("json") ? await response.json() : await response.text();
  assert.equal(
    response.status,
    status,
    `${options.method ?? "GET"} ${path}: expected ${status}, got ${response.status}: ${
      typeof body === "string" ? body.slice(0, 200) : body.message ?? JSON.stringify(body)
    }`,
  );
  return body;
}

function editable(template) {
  return {
    name: template.name,
    version: template.version,
    sections: template.sections.map((section) => ({
      id: section.id,
      name: section.name,
      items: section.items.map((item) => ({
        id: item.id,
        name: item.name,
        comments: item.comments.map(({ id, name, contentHtml }) => ({ id, name, contentHtml })),
      })),
    })),
  };
}

function ids(template) {
  return [
    template.id,
    ...template.sections.flatMap((section) => [
      section.id,
      ...section.items.flatMap((item) => [item.id, ...item.comments.map((comment) => comment.id)]),
    ]),
  ];
}

function hierarchy(template) {
  return template.sections.map((section) => ({
    name: section.name,
    position: section.position,
    items: section.items.map((item) => ({
      name: item.name,
      position: item.position,
      comments: item.comments.map(({ id: _id, ...comment }) => comment),
    })),
  }));
}

assert.equal((await request("/health")).status, "UP");
results.push("Database-backed health check");

const workspace = await request("/workspaces", { method: "POST", status: 201 });
assert.ok(workspace.token.length >= 32, "Workspace credential must have sufficient entropy.");
const token = workspace.token;
const seed = await request(`/templates/${workspace.templateId}`, { token });
assert.deepEqual(seed.counts, { sections: 22, items: 136, comments: 798 });
assert.equal(seed.sourceSha256.toLowerCase(), expectedHash);
results.push("New private workspace opens an actual imported sample");

const form = new FormData();
form.append("file", new Blob([sample]), "Room-by-Room Residential Template-2026-09-18.xls");
form.append("name", "API verification template");
const imported = await request("/templates/import", { method: "POST", body: form, token, status: 201 });
assert.deepEqual(imported.counts, seed.counts);
assert.equal(imported.importReport.sourceRows, 798);
assert.equal(imported.importReport.importedRows, 798);
assert.equal(imported.importReport.sourceColumns.length, 42);
assert.deepEqual(hierarchy(imported), hierarchy(seed));
results.push("Exact sample import, content/metadata equality, hierarchy and ordering");

const update = editable(imported);
update.name = "API verified saved template";
update.sections[0].name = "Inspection Details - verified";
update.sections[0].items[0].name = "General - verified";
update.sections[0].items[0].comments[0].contentHtml =
  '<p>Saved <strong>inspection wording</strong>.</p><script>window.__unsafeImport = true</script>';
const saved = await request(`/templates/${imported.id}`, {
  token,
  method: "PUT",
  body: JSON.stringify(update),
});
assert.ok(saved.version > imported.version);
assert.equal(saved.sections[0].name, update.sections[0].name);
assert.equal(saved.sections[0].items[0].name, update.sections[0].items[0].name);
assert.equal(
  saved.sections[0].items[0].comments[0].originalHtml,
  imported.sections[0].items[0].comments[0].originalHtml,
);
assert.doesNotMatch(saved.sections[0].items[0].comments[0].previewHtml, /<script|onerror\s*=|javascript:/i);
const reopened = await request(`/templates/${saved.id}`, { token });
assert.deepEqual(hierarchy(reopened), hierarchy(saved));
results.push("Saved edits reload from MySQL; original source retained and preview sanitized");

await request(`/templates/${saved.id}`, {
  token,
  method: "PUT",
  body: JSON.stringify(update),
  status: 409,
});
results.push("Stale edits fail with 409 instead of overwriting");

const copied = await request(`/templates/${saved.id}/duplicate`, {
  token,
  method: "POST",
  body: JSON.stringify({ name: "Independent verified copy" }),
  status: 201,
});
assert.deepEqual(hierarchy(copied), hierarchy(saved));
const originalIds = new Set(ids(saved));
assert.ok(ids(copied).every((id) => !originalIds.has(id)), "Copies must have entirely new record IDs.");
const copyUpdate = editable(copied);
copyUpdate.sections[0].items[0].comments[0].contentHtml = "This change belongs only to the copy.";
await request(`/templates/${copied.id}`, { token, method: "PUT", body: JSON.stringify(copyUpdate) });
const unchanged = await request(`/templates/${saved.id}`, { token });
assert.deepEqual(hierarchy(unchanged), hierarchy(saved));
results.push("Deep duplicate has independent IDs and edits never change the original");

await request(`/templates/${copied.id}`, { token, method: "DELETE", status: 204 });
await request(`/templates/${copied.id}`, { token, status: 404 });
assert.deepEqual(hierarchy(await request(`/templates/${saved.id}`, { token })), hierarchy(saved));
results.push("Delete removes the selected template while leaving its original unchanged");

const before = await request("/templates", { token });
const invalid = new FormData();
invalid.append("file", new Blob(["This is not a workbook."]), "invalid.xls");
await request("/templates/import", { token, method: "POST", body: invalid, status: 422 });
const after = await request("/templates", { token });
assert.equal(after.templates.length, before.templates.length);
results.push("Invalid file fails honestly and leaves no partial template");

const other = await request("/workspaces", { method: "POST", status: 201 });
await request(`/templates/${saved.id}`, { token: other.token, status: 404 });
await request("/templates", { token: "invalid-workspace-credential", status: 401 });
results.push("Workspace isolation and invalid-credential rejection");

console.log(JSON.stringify({ base, passed: results.length, checks: results }, null, 2));
