// n8n Code-Node ("Run Once for Each Item") hinter dem GitLab-Webhook:
// GitLab-Event (Merge Request / Note / Pipeline Hook) -> Captain-Aufruf.
// Format: docs/betrieb.md, Abschnitt "Umschlag". Ganzen Inhalt in den Code-Node kopieren,
// oben BOT und CALLBACK anpassen. Danach ein IF auf {{ $json.skip }} und ein HTTP-Request-Node:
//   Methode {{ $json.method }}, URL http://<captain>:8090{{ $json.path }},
//   Header Authorization: Bearer <CAPTAIN_API_TOKEN>, Body (JSON) {{ $json.body }} (nur bei POST).

const BOT = "captain-bot"; // GitLab-Username des Bot-Users (eigene Notizen ignorieren, Erwähnung erkennen)
const CALLBACK = "https://n8n.intern/webhook/captain-review"; // leer = synchron warten

function umschlag(headers, body, { bot = BOT, callback = CALLBACK } = {}) {
  const hook = headers["x-gitlab-event"];
  const project = body.project;
  const skip = (why) => ({ skip: true, why });
  const id = (iid) => `gitlab:${project.id}:mr-${iid}`;
  const mrOf = (m, headSha) => ({
    iid: m.iid,
    url: m.url ?? `${project.web_url}/-/merge_requests/${m.iid}`,
    title: m.title,
    source_branch: m.source_branch,
    target_branch: m.target_branch,
    author_id: m.author_id,
    head_sha: headSha ?? m.last_commit?.id,
  });

  let event;
  let mr;
  const extra = {};
  if (hook === "Merge Request Hook") {
    const m = body.object_attributes;
    const action = m.action;
    if (action === "close" || action === "merge") {
      return { skip: false, method: "DELETE", path: `/api/conversations/${id(m.iid)}` };
    }
    const readyNow = action === "update" && body.changes?.draft?.previous === true && body.changes.draft.current === false;
    if (m.draft) return skip("Draft");
    if (action === "open" || action === "reopen" || readyNow) {
      event = "open";
    } else if (action === "update" && m.oldrev) {
      event = "update";
      extra.push = { oldrev: m.oldrev, newrev: m.last_commit?.id };
    } else {
      return skip(`action ${action}`);
    }
    mr = mrOf(m);
  } else if (hook === "Note Hook") {
    const n = body.object_attributes;
    if (n.noteable_type !== "MergeRequest" || !body.merge_request) return skip("Notiz nicht an einem MR");
    if (body.user?.username === bot) return skip("eigene Notiz");
    if (!String(n.note ?? "").includes(`@${bot}`)) return skip("Bot nicht erwähnt");
    event = "note";
    mr = mrOf(body.merge_request);
    extra.note = { id: n.id, discussion_id: n.discussion_id, author: body.user?.username, body: n.note };
  } else if (hook === "Pipeline Hook") {
    const p = body.object_attributes;
    if (!body.merge_request) return skip("keine MR-Pipeline");
    if (p.status !== "failed" && p.status !== "success") return skip(`Pipeline ${p.status}`);
    event = "pipeline";
    mr = mrOf(body.merge_request, p.sha);
    extra.pipeline = { id: p.id, status: p.status };
  } else {
    return skip(`Hook ${hook}`);
  }

  const envelope = { captain_review: 1, event, project: { id: project.id, path: project.path_with_namespace }, mr, ...extra };
  const request = { agent: "reviewer", sender: "GitLab", text: JSON.stringify(envelope) };
  if (callback) request.callback_url = callback;
  return { skip: false, method: "POST", path: `/api/conversations/${id(mr.iid)}/messages`, body: request, envelope };
}

// --- n8n ---
return { json: umschlag($json.headers, $json.body) };
