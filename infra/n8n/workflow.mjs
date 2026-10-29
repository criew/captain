// Erzeugt den n8n-Workflow "Captain Review" aus captain-umschlag.js, einmal fürs lokale
// Test-Setup und einmal als Copy+Paste-Vorlage (nur Platzhalter-Hosts):
//   node infra/n8n/workflow.mjs        (schreibt beide Dateien neu)
// Der Test (workflow.test.mjs) prüft, dass die eingecheckten Dateien aktuell sind.
//
// Zwei Ketten in einem Workflow:
//   GitLab-Webhook → Umschlag (Code) → Relevant? → Captain (HTTP)
//   Captain-Rückmeldung (Webhook) → Fehler? → Alarm (Stop and Error)
import fs from "node:fs";
import { pathToFileURL } from "node:url";

const here = new URL(".", import.meta.url);
const CODE = fs.readFileSync(new URL("captain-umschlag.js", here), "utf8");

export const TARGETS = {
  lokal: {
    file: new URL("workflows/captain-review.json", here),
    captain: "http://captain:8090",
    callback: "http://n8n:5678/webhook/captain-review",
    bot: "captain-bot",
    id: "captainReview001",
    // feste IDs, damit seed.py die Credentials passend importieren kann
    credentials: { gitlab: "captainGitlabHk1", captain: "captainApiToken1" },
  },
  vorlage: {
    file: new URL("../../docs/anwendungsbeispiel/n8n-captain-review.json", here),
    captain: "http://captain.intern:8090",
    callback: "https://n8n.intern/webhook/captain-review",
    bot: "captain-bot",
    credentials: { gitlab: "", captain: "" },
  },
};

// Code-Node: Datei mit eingesetztem BOT und CALLBACK
export function code(t) {
  const out = CODE
    .replace(/^const BOT = "[^"]*";/m, `const BOT = ${JSON.stringify(t.bot)};`)
    .replace(/^const CALLBACK = "[^"]*";/m, `const CALLBACK = ${JSON.stringify(t.callback)};`);
  if (!out.includes(`const CALLBACK = ${JSON.stringify(t.callback)};`)) throw new Error("CALLBACK nicht gefunden");
  return out;
}

const cred = (id, name) => ({ httpHeaderAuth: { id, name } });

export function workflow(t) {
  const nodes = [
    {
      parameters: {
        content: [
          "## Captain Review",
          "",
          "**Oben:** GitLab-Webhook (Merge Request, Comments, Pipeline) → Umschlag → Captain.",
          "In GitLab: Webhook-URL `…/webhook/gitlab`, Secret Token = Wert der Credential **GitLab Webhook** (Header `X-Gitlab-Token`).",
          "",
          "**Unten:** Rückmeldung von Captain (`callback_url`). `FEHLER:` oder `error` → Alarm. Den Stop-and-Error-Node durch Mail/Chat ersetzen.",
          "",
          "Credential **Captain API**: Header Auth, Name `Authorization`, Wert `Bearer <CAPTAIN_API_TOKEN>`.",
          "Im Code-Node oben `BOT` und `CALLBACK` anpassen. Format: docs/betrieb.md, „Umschlag“.",
        ].join("\n"),
        height: 300,
        width: 520,
      },
      id: "6f0b7c4e-0000-4000-8000-000000000001",
      name: "Hinweis",
      type: "n8n-nodes-base.stickyNote",
      typeVersion: 1,
      position: [-560, -60],
    },
    {
      parameters: { httpMethod: "POST", path: "gitlab", authentication: "headerAuth", options: {} },
      id: "6f0b7c4e-0000-4000-8000-000000000002",
      name: "GitLab-Webhook",
      type: "n8n-nodes-base.webhook",
      typeVersion: 2,
      position: [0, 0],
      webhookId: "6f0b7c4e-0000-4000-8000-0000000000a1",
      credentials: cred(t.credentials.gitlab, "GitLab Webhook"),
    },
    {
      parameters: { mode: "runOnceForEachItem", jsCode: code(t) },
      id: "6f0b7c4e-0000-4000-8000-000000000003",
      name: "Umschlag",
      type: "n8n-nodes-base.code",
      typeVersion: 2,
      position: [220, 0],
    },
    {
      parameters: {
        conditions: {
          options: { caseSensitive: true, leftValue: "", typeValidation: "loose", version: 2 },
          conditions: [{
            id: "6f0b7c4e-0000-4000-8000-0000000000c1",
            leftValue: "={{ $json.skip }}",
            rightValue: "",
            operator: { type: "boolean", operation: "false", singleValue: true },
          }],
          combinator: "and",
        },
        options: {},
      },
      id: "6f0b7c4e-0000-4000-8000-000000000004",
      name: "Relevant?",
      type: "n8n-nodes-base.if",
      typeVersion: 2.2,
      position: [440, 0],
    },
    {
      parameters: {
        method: "={{ $json.method }}",
        url: `=${t.captain}{{ $json.path }}`,
        authentication: "genericCredentialType",
        genericAuthType: "httpHeaderAuth",
        sendBody: true,
        specifyBody: "json",
        jsonBody: "={{ JSON.stringify($json.body ?? {}) }}",
        options: { timeout: 30000 },
      },
      id: "6f0b7c4e-0000-4000-8000-000000000005",
      name: "Captain",
      type: "n8n-nodes-base.httpRequest",
      typeVersion: 4.2,
      position: [660, -20],
      // DELETE einer unbekannten Unterhaltung (404) ist kein Fehler
      onError: "continueRegularOutput",
      credentials: cred(t.credentials.captain, "Captain API"),
    },
    {
      parameters: { httpMethod: "POST", path: "captain-review", options: {} },
      id: "6f0b7c4e-0000-4000-8000-000000000006",
      name: "Captain-Rückmeldung",
      type: "n8n-nodes-base.webhook",
      typeVersion: 2,
      position: [0, 300],
      webhookId: "6f0b7c4e-0000-4000-8000-0000000000a2",
    },
    {
      parameters: {
        conditions: {
          options: { caseSensitive: true, leftValue: "", typeValidation: "loose", version: 2 },
          conditions: [{
            id: "6f0b7c4e-0000-4000-8000-0000000000c2",
            leftValue: "={{ !!$json.body.error || String($json.body.answer ?? '').trim().startsWith('FEHLER:') }}",
            rightValue: "",
            operator: { type: "boolean", operation: "true", singleValue: true },
          }],
          combinator: "and",
        },
        options: {},
      },
      id: "6f0b7c4e-0000-4000-8000-000000000007",
      name: "Fehler?",
      type: "n8n-nodes-base.if",
      typeVersion: 2.2,
      position: [220, 300],
    },
    {
      parameters: {
        errorMessage: "=Review {{ $json.headers['x-captain-conversation'] }}: {{ $json.body.error || $json.body.answer }}",
      },
      id: "6f0b7c4e-0000-4000-8000-000000000008",
      name: "Alarm",
      type: "n8n-nodes-base.stopAndError",
      typeVersion: 1,
      position: [440, 280],
    },
  ];
  const to = (node) => ({ main: [[{ node, type: "main", index: 0 }]] });
  return {
    name: "Captain Review",
    nodes,
    connections: {
      "GitLab-Webhook": to("Umschlag"),
      Umschlag: to("Relevant?"),
      "Relevant?": { main: [[{ node: "Captain", type: "main", index: 0 }], []] },
      "Captain-Rückmeldung": to("Fehler?"),
      "Fehler?": { main: [[{ node: "Alarm", type: "main", index: 0 }], []] },
    },
    settings: { executionOrder: "v1" },
    ...(t.id ? { id: t.id, active: true } : {}),
  };
}

export const render = (t) => `${JSON.stringify(workflow(t), null, 2)}\n`;

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  for (const t of Object.values(TARGETS)) fs.writeFileSync(t.file, render(t));
}
