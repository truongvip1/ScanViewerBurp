# Timed Login Refresh Review

Reviewed on 2026-10-29. Production sources and the distributable JAR were not modified during this review.

Validation used freshly compiled production sources and the existing simulated Montoya fixtures. No network requests were sent. The existing regression suite passed. Additional cases below expose gaps in that suite. Live Burp Dashboard behavior has not been tested.

## Reproduced findings

1. **P1: Saving an unchanged profile stops the timer and blocks the next probe.**
   Saving the same settings changed revision 4 to 5; only the initial login ran after the deadline, and the next probe was dropped. Disabling the timer after token-2 was obtained also let the next probe use its original token-1.
   Sources: LoginMacroStore.java:191, ScannerMacroGate.java:83, ScannerMacroGate.java:150.
   Proposal: separate scheduling changes from execution-profile changes; avoid revision changes on no-op saves; keep verified credentials independently of whether repetition is enabled.

2. **P1: The initial login result can be labelled with a newer, unverified profile revision.**
   While initial login was waiting, the fixture added a required response header that the response did not contain. The audit still started and its next probe was allowed. registerSession uses the current profile revision rather than the revision that produced the result.
   Sources: ScannerMacroGate.java:44, ScanQueue.java:342.
   Proposal: carry the exact immutable profile/version with every result and revalidate or rerun before dispatch when configuration changed.

3. **P1: Cancelling a timed session can be overwritten by refresh publication.**
   A controlled interleaving delayed refresh inside the store's baseline-update notification, after the cancellation check. Calling clearSession cleared the session, but refresh then wrote a new timed session and scheduled another login. A third login was observed after cancellation.
   Source: ScannerMacroGate.java:125.
   This is a deterministic concurrency test using an injected delay, not an observation from a live Burp run.
   Proposal: publish session replacement and timer installation atomically against a lifecycle generation; a stopped generation must never become active again.

4. **P1: Timed-login failure is not reflected in audit completion, and Retry cannot recover.**
   The scheduled refresh returned 401. After the simulated Scanner audit finished, Queue showed COMPLETED. Retry as new followed by Resume still left one waiting item paused because the failure latch is scoped to profile revision.
   Sources: ScanQueue.java:286, ScanQueue.java:316, ScannerMacroGate.java:175.
   Proposal: retain authentication failure in the audit result and give a new attempt its own failure state. Provide explicit Retry login recovery that clears the block only after successful verification.

5. **P2: Token rotation is inferred as a Scanner payload.**
   Scanner submitted its unchanged authenticated seed with token-1 after the timer obtained token-2. The outgoing message correctly contained token-2, but Timeline reported payload "1" and insertion point "header: Authorization (value changed, inferred)".
   Sources: ScannerMacroGate.java:130, ScanSessionStore.java:348, ScanSession.java:164.
   Proposal: distinguish the seed originally submitted to Scanner from current authentication values; retain the relevant authentication/baseline version per probe for Payload and Compare.

6. **P2: Dispatch resets timer age after login completed while Queue was paused.**
   With a 1,000 ms interval, initial login finished while Queue was paused; Queue resumed roughly 700 ms later. A probe at login age 1,169 ms still used token-1 because the timer restarted at dispatch.
   Source: ScannerMacroGate.java:48.
   Proposal: carry the original completion time and schedule only the remaining interval. Support a session lifetime with a refresh lead time, optionally derived from a selected login-response expiry field.

7. **P2: Cached sessions still re-extract values on every probe.**
   With one successful login and a 100 KB JSON response, 25 probes caused 25 additional JSON-body reads/extractions. This count is not an end-to-end performance benchmark. Baseline updates also queue metric recomputation for the session history.
   Sources: LoginMacroRunner.java:129, LoginMacroRunner.java:422, ScanSessionStore.java:494.
   Proposal: resolve source values once per verified run, apply target replacements per probe, preserve cookie scope/expiry checks, and invalidate only analysis affected by the baseline change.

## Additional product improvements

- Display Last success, Next refresh, session age, duration, and success/failure counts.
- Use explicit states: Active, Refreshing, Failed, and Stopped.
- Add Refresh now, with Pause refresh / Resume refresh / Retry login in a compact actions menu.
- Give every refresh a run ID, cause (initial, scheduled, manual, expired), step traces, and links from probes using its credentials.
- Label blocked timed-login probes accurately; the shared fallback still says "Not sent (Macro all)".
- If adding response-triggered refresh, make conditions opt-in and merge simultaneous triggers into one refresh. Do not automatically replay Scanner probes that may change target state.

## Evidence

- reproduction-results.txt: six scenario runs, including the controlled cancellation interleaving.
- extraction-results.txt: cached binding extraction count.
- regression-results.txt: existing suite result.
- TimedRefreshReview.java: isolated reproductions using existing HTTP-message and persistence fixtures.

Run all additional scenarios:

    java -ea -cp "lib/montoya-api-2026.7.jar;target/review-timed-login/classes" com.scanviewer.TimedRefreshReview
