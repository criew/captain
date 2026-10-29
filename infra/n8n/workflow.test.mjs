// Tests für den n8n-Workflow "Captain Review": node --test infra/n8n/workflow.test.mjs
import assert from "node:assert/strict";
import fs from "node:fs";
import { test } from "node:test";
import { TARGETS, code, render, workflow } from "./workflow.mjs";

for (const [name, t] of Object.entries(TARGETS)) {
  test(`${name}: eingecheckte Datei ist aktuell (node infra/n8n/workflow.mjs)`, () => {
    assert.equal(fs.readFileSync(t.file, "utf8"), render(t));
  });

  test(`${name}: Verbindungen zeigen auf vorhandene Nodes`, () => {
    const w = workflow(t);
    const names = new Set(w.nodes.map((n) => n.name));
    for (const [from, c] of Object.entries(w.connections)) {
      assert.ok(names.has(from), from);
      for (const out of c.main) for (const e of out) assert.ok(names.has(e.node), e.node);
    }
  });

  test(`${name}: Code-Node läuft wie in n8n (ein Item, $json)`, () => {
    const run = new Function("$json", code(t));
    const $json = {
      headers: { "x-gitlab-event": "Merge Request Hook" },
      body: {
        object_kind: "merge_request", user: { username: "mk" },
        project: { id: 7, path_with_namespace: "team/app", web_url: "https://git.intern/team/app" },
        object_attributes: { action: "open", iid: 3, title: "T", source_branch: "f", target_branch: "main",
          author_id: 1, draft: false, last_commit: { id: "abc" } },
      },
    };
    const { json } = run($json);
    assert.equal(json.method, "POST");
    assert.equal(json.path, "/api/conversations/gitlab:7:mr-3/messages");
    assert.equal(json.body.callback_url, t.callback);
    assert.equal(json.body.agent, "reviewer");
  });
}

test("Vorlage enthält nur Platzhalter-Hosts (*.intern) und keine Credential-IDs", () => {
  const text = fs.readFileSync(TARGETS.vorlage.file, "utf8");
  const hosts = new Set([...text.matchAll(/https?:\/\/([A-Za-z0-9.-]+)/g)].map((m) => m[1]));
  for (const h of hosts) assert.match(h, /\.intern$/, `Host ${h}`);
  for (const n of workflow(TARGETS.vorlage).nodes) {
    for (const c of Object.values(n.credentials ?? {})) assert.equal(c.id, "");
  }
});
