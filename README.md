# Active Scan Timeline Viewer

`Active Scan Timeline Viewer` is a Burp Suite Professional extension for reviewing **all HTTP traffic emitted by the native Active Scanner**. It is not restricted to findings: every Scanner request is listed, along with its response when received.

## Interface

- **All captured traffic** is an always-on tab that contains every request from Burp Scanner after the extension loads, including scans that produce no findings, plus login-macro exchanges started by Queue or Macro all.
- Tabs **1**, **2**, **3**, and so on are created when requests are added to the scan queue. Each tab contains traffic from one request's audit. Waiting tabs show `Queued` until their turn starts.
- Each tab uses a compact Intruder-like list with request, inferred changed payload, inferred insertion point, numeric status/length/duration, payload reflection, bookmark, note, error and timeout. Tick the **Bookmark** checkbox directly in the table to mark an interesting request. Response-state, delta-length, status-change, similarity, and extraction data remain available to filters, exports, and the comparison detail rather than taking permanent table columns.
- Selecting a row displays the raw request and response underneath the list. When no response was observed, the panel remains empty and clearly explains the state; it never fabricates an HTTP response.
- **Advanced filters** combine status (`4xx`, `5xx`, or status changed), response state, slow-response threshold, bookmark state, and text/regex search with **AND** or **OR**. Search can target metadata, raw request, raw response, or both; raw-message searching runs in a background worker. Filter presets are saved in Burp preferences.
- **Highlight rules** support `Name | #RRGGBB | regex` for metadata, with built-in colors for 5xx, no response, slow response and bookmarks. **Extraction rules** support `Name | REGEX | expression`, `Name | HEADER | header-name`, and `Name | JSON | $.field`; results are retained with the row and available to filtering and export.
- Double-click a numbered tab, or right-click it and choose **Rename tab...**, to rename it. Its right-click menu also exposes **Queue state history...** for retained Queue tabs. Right-click a row for Repeater, Comparer, copy, bookmark and note actions.
- **Pause display** pauses only Swing updates: capture and persistence continue, including response extraction. Resume rebuilds directly from the store. Traffic refreshes are coalesced without cancelling a slow filter pass, so continuous Scanner traffic cannot starve the table. Selecting a request opens its original raw request by default; **Compare with base** remains available as a component-aware analysis view: Query, Headers, Cookies, JSON, Form, Body and a line-aligned raw diff each show `location -> original -> changed` in color. The overview also shows base-response metrics (`Delta length`, status change, exact match, normalized similarity and payload reflection). Request comparisons are calculated off the Swing thread and cached per row.
- The main toolbar keeps only frequent actions. Filters, rules, import/export, restoring closed tabs and layout reset are under **More...**. The queue similarly puts recovery, retry, removal and history actions under **More...**. Saved divider positions are applied after Burp lays out the tab, then clamped to the usable window size.

## Start an Active Scan with Timeline

1. In Proxy history, Target, Repeater, or another HTTP-message view, right-click the request.
2. Open Burp's **Extensions** submenu and choose **Active scan with Timeline**.
3. The extension adds the request to the queue, creates a numbered tab, and starts the queue. If another audit is already running, the new request waits for its turn.

Choosing **Active scan with Timeline** again appends another request. Only one extension-started audit runs at a time. New traffic switches to the next tab only when its audit starts; late responses remain linked to the original request and tab.

The action calls Montoya `Scanner.startAudit`, so Burp creates a real Scanner audit task that is visible in the Dashboard. The selected tab's status bar and tooltip show the scanner's `statusMessage` plus its request, insertion-point, finding, and error counts. Burp's public extension API does not provide a definitive task-completed callback, so the extension never treats a quiet traffic stream as completion. All individual requests are retained in **All captured traffic**.

The **Active scan with Timeline** action uses Montoya's built-in `LEGACY_ACTIVE_AUDIT_CHECKS` configuration. It does not read or reuse a custom scan configuration selected in Burp's native scan wizard, because that configuration is not exposed by the public API.

## Sequential scan queue

1. Select one or more requests in Proxy history, Target, Repeater, or another HTTP-message view.
2. Right-click **Extensions > Add to scan queue (N)** to stage the selected requests. Each receives its own numbered tab in the order supplied by Burp. Requests appended to an already running queue run after its current items.
3. Open **Audit Timeline > Scan queue...**, then click **Start / resume queue**. To enqueue and start immediately from the context menu, use **Active scan with Timeline** for a single request or **Scan selected requests sequentially** for multiple requests.
4. The queue window shows order, tab name, request, state, and status. Select a row then use **View request**, or double-click the row, to open the complete original queued request in a resizable, copyable viewer. Use **Move up** / **Move down** to reorder waiting items. **Remove from queue** is available only for waiting items and, after confirmation, permanently deletes that Queue item, its Timeline tab, and all saved request/response history. The removal first saves a durable marker; if this cannot be saved, the request remains in Queue and the extension reports the failure. State colors distinguish queued, authenticating, dispatching, running, completed, failed and recovery-required items. **Cancel macro** stops an in-flight login macro before Scanner dispatch.

**Pause queue**, **Pause display**, and pausing an audit in Burp Dashboard are separate controls. Pausing the queue leaves the current audit running; pausing display leaves capture, saving, and queue dispatch running. Use Dashboard to pause the audit itself. Clearing all traffic or deleting the current audit's history is blocked while it owns a queue slot.

The extension polls [Montoya's Audit.statusMessage()](https://portswigger.github.io/burp-extensions-montoya-api/javadoc/burp/api/montoya/scanner/audit/Audit.html) once per second. It advances on explicitly recognized completion text such as `finished`, `completed`, or `Audit finished.`. This API exposes a free-text status, not a completion callback or stable status enum. Blank, waiting, paused, or unrecognized messages hold the queue; no traffic-idle timeout is used. If Burp reports completion in an unfamiliar form, verify it in Dashboard and use **Confirm current audit finished...**. A failed audit or API error pauses dispatch for review.

Queue ordering isolates extension-started audits only. Other native or extension Scanner tasks still contribute traffic to the current capture window because Montoya does not expose their task IDs on HTTP callbacks. Avoid running unrelated Scanner audits concurrently when you need clean per-request logs.

Waiting requests, their original base request/response, effective authenticated scan request/response, and queue states are saved in the Burp project. After reload the queue is paused. A previously dispatched, running, or `AUTHENTICATING` audit becomes `UNKNOWN_DISPATCH`; the extension cannot reattach to it and will never automatically repeat it. Before resuming, check its Dashboard task and confirm it has stopped. A legacy `REMOVED` queue row is migrated to `NONE`, hidden from Queue, and retains an audit-trail entry. Unloading the extension preserves native Dashboard tasks. Session archives import as history and do not schedule scans.

## Login macro and Macro all

Login macro has three mutually exclusive radio modes. **Off — configure and test only** performs no automatic login. **Run once before each queued audit** refreshes authentication before starting an audit. **Run before every queued Scanner request (Macro all)** runs the complete macro before each outgoing payload probe belonging to the active Queue item. Direct scans started from Burp are not configured by this feature.

1. In Proxy history, Repeater, Target, or Timeline, select the login requests in their intended order. Right-click and choose **Extensions > Add selected request(s) to login macro**. They become **Step 1**, **Step 2**, **Step 3**, and so on.
2. Open **Audit Timeline > Scan queue... > More... > Login macro...**, then select **Run before every queued Scanner request (Macro all)**. Every request in Scan Queue is covered automatically, so no Queue request or target needs to be selected. Direct Burp scans, Proxy, Repeater and macro traffic are unaffected when no Queue item is active. Older projects retain their once-per-audit setting; select Macro all explicitly after upgrading.
3. In **Add binding...**, choose **Scan request (Queue / Macro all)** under **Request to modify**. The optional Queue sample provides parsed target keys; without a sample, enter the target key manually. Choose a macro step and select a field in its request or response sample. Response headers, cookies, JSON paths and manual expressions are supported. The same bindings apply to each live Scanner request in Macro all mode. If the request has `Authorization: Bearer ...` and the fresh source is a bare token, it retains `Bearer `; a source with a scheme is not prefixed again. Missing, null, empty or whitespace-only Authorization values stop the request.
4. Request values from an earlier step use its prepared request, including bindings applied during this run. References to the current or a later step use that step's recorded template. Response sources must precede their target. Reordering that changes any of these dependencies is blocked; adjust the affected bindings before reordering.
5. Use **Success checks...** to set the timeout for each macro request, accepted HTTP status range(s), optional required response cookie/header/JSON field, and the step whose response proves login succeeded. In the same footer, choose **Example from queue** and click **Test macro...** to run the flow without starting Scanner. The result adds that request as the final item after the macro steps, with its effective request and live response after final token/cookie bindings. The example is sent only as part of the test: it is not dispatched to Scanner or added to Queue history. Results retain completed, binding-failed, request-failed and not-executed steps. Response statuses are colour-coded: 2xx green, 3xx blue, and 4xx/5xx red.

For a guided multi-step flow, select a later request in **Macro requests** and choose **Bind earlier values...**. Target keys are parsed from that exact request; no other request is used for its keys. Each row independently selects an earlier step and one parsed request or response value from it. Use **+ Add key/value** to configure several inputs at once, for example passing a CSRF value and login identifier from **Step 1** to **Step 2**, then values from **Step 1** and **Step 2** to **Step 3**. A value copied from an already-run request uses its live, bound request value rather than its old saved placeholder.

Macro all uses [Montoya's before-send HTTP handler](https://portswigger.github.io/burp-extensions-montoya-api/javadoc/burp/api/montoya/http/handler/HttpHandler.html). Each Scanner request matching the active Queue item's transport origin waits for fresh macro responses and success checks, receives its bindings, then continues through Burp's normal transport. Payloads outside the bound fields are retained. A binding overwrites its target field even if Scanner is testing that field. Macro sequences are serialized, while Scanner requests may still be in flight concurrently: for single-use tokens or sessions invalidated by the next login, set the Scanner resource pool's maximum concurrent requests to **1**. Each probe adds a full macro run, increasing scan time and login traffic. Montoya does not expose an audit-task ID on the HTTP callback, so avoid running an unrelated native scan against the same origin while a Queue audit is active.

On timeout, cancellation, failed verification or missing binding data, Macro all drops the probe and records **Not sent (Macro all)** with its error; no response is fabricated. Subsequent Queue probes stay blocked to avoid repeated failed logins, and Queue pauses advancement. Fix the profile or switch Macro all off and back on to retry; check the audit in Dashboard because dropped probes are not replayed. **Cancel macro** also cancels a running per-request macro. Macro all continues for the running Queue audit when Queue advancement is paused; pause that task in Dashboard to stop its traffic. Timeline records the post-binding message and uses Burp's original message ID to correlate responses. Payload inference uses the pre-binding probe so fresh auth values do not become inferred Scanner payloads.

Raw `Cookie:` request headers and `Set-Cookie:` response headers are expanded into individual cookie names and values. Cookie names and parameter names are case-sensitive. Multiple request Cookie header lines and quoted values are handled without discarding other cookies. Same-name cookies with ambiguous scopes are rejected rather than selecting an arbitrary value.

When a token needs a custom extraction rule, choose `RESPONSE_REGEX` as the value source type, enter a Java regular expression, and use **Test regex**. The test runs against the selected macro step's saved full response, including headers such as `Set-Cookie` and the body. The first populated capture group is used; without a capture group, the full match is used. The live macro always reruns the same expression against its fresh response.

New bindings retain the selected source location (URL, body, cookie, JSON, or header). Existing name-only parameter bindings still load, but pause the macro if the name matches several locations; edit the binding and reselect the intended field to resolve it. Only one binding can write each target key.

Binding batches are saved atomically. Reload validates every step and binding, the step order, and the binding count when available in the saved format. A damaged profile reports the offending record, blocks queue dispatch, and protects the original saved data from subsequent edits. Repair the saved project data or deliberately use **Clear macro** to reset the profile. Older profiles without a binding count remain readable; already-missing records in that older format cannot be detected from a count.

The macro network operation runs outside the Queue control worker, so **Pause queue** remains responsive while login waits for a response. In once-per-audit mode, an `AUTHENTICATING` checkpoint is saved before each macro step. Macro traffic is retained in the audit tab; a timeout, cancellation or failed check marks it `FAILED` and pauses Queue before dispatch. The original and effective authenticated baseline are retained separately. Macro all runs after the audit has started and preserves its Queue state; a failure blocks outgoing probes instead.

Macro requests, bindings, and any credentials embedded in those raw requests are stored in Burp project extension data. Treat the project and session archives as sensitive; clear the macro when moving to a different target.

## What cannot be obtained from the public API

- Burp does not expose the native Scanner's private audit-task ID, exact payload, exact insertion-point metadata, or individual network exception to extensions.
- `Payload` and `Insert point` are marked as **best-effort inferences** by comparing requests with the scan's base request.
- Request state is explicit: `Waiting`, `Response received`, `No response after 120 seconds`, or `Unknown reason`. A late response replaces the provisional state.

The request, response, status, response time, and response length are recorded directly from Montoya HTTP callbacks.

## Persistence across Burp restarts

The extension saves tab metadata, base request/response, raw captured request/response bodies, notes, bookmarks, metrics and extraction output into **Burp project extension data**. Version 2 stores only changed sessions/rows rather than rebuilding the complete history. Dirty markers are acknowledged only after the v2 root has been attached successfully, so a failed first save is retried with the same rows. The status bar shows `Saving changes`, `Saved at...`, or `Save failed`; a failure remains visible until the next write actually begins. A restore error skips only the damaged record, reports the skipped-record count, and never clears the rest of the in-memory history.

On reopening the same saved project, the tabs and all captured rows are restored. Historical tabs from earlier versions show `Restored - scan not running`; queued sessions retain their queue state, while previously dispatched audits are marked `UNKNOWN_DISPATCH`. A closed tab only hides the UI: use **More... > Reopen closed tabs** to bring it back. **More... > Delete tab history** removes one session independently.

To retain data after Burp closes, use a named project and save it. Montoya keeps project extension data only in memory when Burp is started without a project file. For a Temporary Project, use **More... > Export session...** to create an `.ascan` archive; it includes every row in the selected session plus raw request/response bytes and can be restored with **More... > Import session...**. Archive and CSV reads/writes run off the Swing thread; archive export writes a temporary file before replacing the destination. Import validates the complete archive before adding a session, enforces a 256 MiB compressed limit, 512 MiB expanded limit, 32 MiB raw-message limit, and 100,000-record limit, shows progress, and can be cancelled before the atomic commit. The project file and archive contain HTTP bodies and potentially cookies or credentials; handle them as sensitive data. **Clear captured traffic** also clears saved Timeline data.

## Build

Requirements: JDK 17+ and PowerShell. Maven/Gradle are not required.

```powershell
Set-Location D:\ExtBurp\ScanViewer
.\build.ps1
```

The script downloads the Montoya API from Maven Central on first run and creates:

`target\active-scan-timeline-viewer.jar`

## Regression checks

```powershell
Set-Location D:\ExtBurp\ScanViewer
.\test.ps1
```

The dependency-free checks cover an interrupted first v2 save followed by retry/reload, corrupt project rows, malformed archive import, a response arriving after the provisional timeout, non-cascading raw diff, strict JSON extraction, response metrics for one-digit security flags, and combined AND/OR raw-response filtering. Login macro tests cover typed source selection with duplicate names across URL/body/cookie/JSON, case-sensitive and quoted cookies, multiple Cookie/Set-Cookie headers, ambiguous sources, atomic batch failure/retry, damaged or missing persisted bindings, dispatch prevention after a failed restore, and protection of live request dependencies during reordering. They also cover response-sample persistence, request/response value chaining, and a fresh macro run before every queued audit. Queue tests exercise sequential dispatch, traffic ownership and late responses across tabs, pause/resume, reorder/removal with state history, migration of legacy removed rows, ambiguous completion text, startup and status-read failures, recovery after reload, and stale manual completion confirmations. These use simulated Montoya audits and persistence; live Dashboard behavior must be checked in Burp Professional.

Macro all regression tests cover fresh tokens on multiple probes in one Queue audit, direct scans with no Queue session, origin and tool filtering, recursive-callback prevention, post-binding capture with late responses, failure blocking, cancellation through Queue, persistence failure and migration from the old flag. They simulate Montoya callbacks; end-to-end behavior still needs verification in Burp Professional.

## Install

1. In Burp Suite Professional, go to **Extensions** > **Installed** > **Add**.
2. Select `target\active-scan-timeline-viewer.jar` as a Java extension.
3. Use the **Audit Timeline** suite tab while running native Active Scans.
