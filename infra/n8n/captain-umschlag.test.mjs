// Unit-Tests für den n8n-Code-Node: node --test infra/n8n/captain-umschlag.test.mjs
import assert from "node:assert/strict";
import fs from "node:fs";
import { test } from "node:test";

// Datei ohne den n8n-Teil (return mit $json) als Funktion laden
const source = fs.readFileSync(new URL("./captain-umschlag.js", import.meta.url), "utf8");
const umschlag = new Function(`${source.slice(0, source.indexOf("// --- n8n ---"))}\nreturn umschlag;`)();

const project = { id: 10500, path_with_namespace: "team/playground", web_url: "https://git.intern/team/playground" };
const mrAttrs = {
  iid: 15, url: "https://git.intern/team/playground/-/merge_requests/15", title: "Update BOM", author_id: 1151,
  source_branch: "feature/x", target_branch: "main", draft: false,
  last_commit: { id: "ce1ddd5e522afe500a617cea5029d8f4c8355e7c" },
};
const MR = { "x-gitlab-event": "Merge Request Hook" };
const mrHook = (attrs, changes = {}) => ({ object_kind: "merge_request", user: { username: "mk" }, project, object_attributes: { ...mrAttrs, ...attrs }, changes });

test("open: Umschlag aus dem Merge Request Hook", () => {
  const r = umschlag(MR, mrHook({ action: "open" }), { callback: "https://n8n/cb" });
  assert.equal(r.method, "POST");
  assert.equal(r.path, "/api/conversations/gitlab:10500:mr-15/messages");
  assert.deepEqual(r.body, { agent: "reviewer", sender: "GitLab", text: r.body.text, callback_url: "https://n8n/cb" });
  assert.deepEqual(JSON.parse(r.body.text), {
    captain_review: 1, event: "open", project: { id: 10500, path: "team/playground" },
    mr: { iid: 15, url: mrAttrs.url, title: "Update BOM", source_branch: "feature/x", target_branch: "main",
      author_id: 1151, head_sha: mrAttrs.last_commit.id },
  });
  assert.equal(umschlag(MR, mrHook({ action: "reopen" })).envelope.event, "open");
  assert.equal(umschlag(MR, mrHook({ action: "open" }), { callback: "" }).body.callback_url, undefined);
});

test("update: nur mit neuem Push, Draft → Ready zählt als open", () => {
  const push = umschlag(MR, mrHook({ action: "update", oldrev: "9f8e7d6" })).envelope;
  assert.equal(push.event, "update");
  assert.deepEqual(push.push, { oldrev: "9f8e7d6", newrev: mrAttrs.last_commit.id });
  assert.deepEqual(umschlag(MR, mrHook({ action: "update" })), { skip: true, why: "action update" });
  assert.equal(umschlag(MR, mrHook({ action: "update" }, { draft: { previous: true, current: false } })).envelope.event, "open");
  assert.equal(umschlag(MR, mrHook({ action: "open", draft: true })).skip, true);
  assert.equal(umschlag(MR, mrHook({ action: "approved" })).skip, true);
});

test("close/merge: DELETE", () => {
  for (const action of ["close", "merge"]) {
    assert.deepEqual(umschlag(MR, mrHook({ action })), { skip: false, method: "DELETE", path: "/api/conversations/gitlab:10500:mr-15" });
  }
});

test("note: nur Erwähnungen des Bots, nie eigene Notizen", () => {
  const H = { "x-gitlab-event": "Note Hook" };
  const note = (user, text, type = "MergeRequest") => ({
    user: { username: user }, project, merge_request: { ...mrAttrs, url: undefined },
    object_attributes: { id: 9001, discussion_id: "abc", noteable_type: type, note: text },
  });
  const r = umschlag(H, note("mk", "@captain-bot warum?"));
  assert.equal(r.path, "/api/conversations/gitlab:10500:mr-15/messages");
  assert.equal(r.envelope.mr.url, "https://git.intern/team/playground/-/merge_requests/15");
  assert.deepEqual(r.envelope.note, { id: 9001, discussion_id: "abc", author: "mk", body: "@captain-bot warum?" });
  assert.equal(umschlag(H, note("captain-bot", "@captain-bot Review fertig")).skip, true);
  assert.equal(umschlag(H, note("mk", "ohne Erwähnung")).skip, true);
  assert.equal(umschlag(H, note("mk", "@captain-bot", "Issue")).skip, true);
});

test("pipeline: nur fertige MR-Pipelines", () => {
  const H = { "x-gitlab-event": "Pipeline Hook" };
  const pipe = (status, mr = { iid: 15, title: "Update BOM", source_branch: "feature/x", target_branch: "main", url: mrAttrs.url }) =>
    ({ project, merge_request: mr, object_attributes: { id: 693031, status, sha: "ce1ddd5" } });
  const r = umschlag(H, pipe("failed")).envelope;
  assert.equal(r.event, "pipeline");
  assert.deepEqual(r.pipeline, { id: 693031, status: "failed" });
  assert.equal(r.mr.head_sha, "ce1ddd5");
  assert.equal(umschlag(H, pipe("success")).envelope.pipeline.status, "success");
  assert.equal(umschlag(H, pipe("running")).skip, true);
  assert.equal(umschlag(H, pipe("failed", null)).skip, true);
  assert.equal(umschlag({ "x-gitlab-event": "Push Hook" }, { project }).skip, true);
});
