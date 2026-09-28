# SOX controls that apply to this repository

Owner: Controls and Assurance, with Payments Platform as the implementing team.
Scope: every code path in `payments/` and `lending/` that moves money, changes an
account balance, or changes the state of a loan.

Auditors sample pull requests from this repository twice a year. The three
controls below are the ones they test, and each one has a matching engineering
rule that reviewers are expected to enforce on every pull request.

## CTRL-1: Audit logging of money movement

Every operation that moves money or changes account state must emit a structured
audit event through `AuditLogger` before the response is returned.

An audit event is only complete when it carries all of:

- `requestId`, so the event can be tied back to the inbound request
- `actor`, the authenticated user or system identity that caused the change
- `action`, a stable verb such as `payment.captured` or `loan.disbursed`
- `amount`, as a `Money` value, and the affected account or loan identifier
- `outcome`, either success or the failure reason

Free text application logs do not satisfy this control. If an operation can fail
part way through, the audit event records the outcome that actually happened, not
the one that was intended.

## CTRL-2: No swallowed failures

A failure must never be reported to the caller as a success.

When code calls a processor, a ledger or a repository that can fail, the failure
is logged and surfaced to the caller as a failure result. Catching an exception
and falling through to a success path is the specific pattern this control exists
to prevent, because it produces a customer record that disagrees with the
processor's record and the discrepancy is only found at reconciliation.

Practically, this means:

- No empty `catch` blocks, and no `catch` that returns a success result
- No `runCatching { ... }.getOrNull()` on a money movement call
- Retries are explicit and bounded, and the final failure is still surfaced

## CTRL-3: No sensitive data in logs

Card numbers, bank account numbers, national identifiers, access tokens and API
keys must never reach a log sink in readable form.

All logging from `payments/` and `lending/` goes through `RedactingLogger`, which
masks these fields. Debugging output is held to the same standard as production
logging, because our log retention is the same for both. If you need to correlate
a payment while debugging, log the `requestId` or the payment identifier, never
the instrument details.

## Evidence we hand to auditors

For a sampled pull request we show the diff, the review that approved it, and the
audit events the changed code path produces in staging. A pull request that
touches money movement without an audit event is an exception that has to be
written up, so reviewers are asked to catch it before merge.
