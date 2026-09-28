# Circular engineering conventions

Org-wide conventions for Circular's backend repositories. Module level
`.greptile/` directories add to these, they do not replace them.

## Money

Amounts are always the `Money` value type (`BigDecimal` plus a currency). A
`Double` or `Float` amount is a bug even when the test passes, because the error
only shows up at reconciliation. Currency mixing is rejected at the type level, so
code that unwraps `Money` to do arithmetic on the raw `BigDecimal` is working
around a safety check.

## Service shape

Services take their collaborators through the constructor and depend on
interfaces. Public service methods return a sealed result type, so callers have to
handle failure explicitly. Controllers translate the sealed result into an HTTP
status and never leak an exception type to the client.

## Idempotency

Any endpoint that can move money accepts an `Idempotency-Key` header and stores
the outcome against that key. Retries with the same key return the stored outcome.
Changing or removing that header on either side of the wire is a breaking change
to the payment contract, not a cleanup, because the retry path stops being safe.

## Logging and audit

`RedactingLogger` is the only logger used in code that handles customer data.
Money movement and account state changes also write a structured audit event
through `AuditLogger`, carrying request ID, actor, action, amount and outcome.
Audit events are the evidence our SOX controls are tested against, so they are not
optional and they are not something to add in a follow up pull request.

## Error handling

Failures are logged and surfaced. We do not catch an exception and return a
success, we do not return `null` to mean failure, and we do not leave an empty
`catch` block. Where a partial failure is possible the audit event records what
actually happened.

## Internal tools

`internal-tools/` holds operator scripts that are not customer facing. Standards
there are looser: plain stdout is acceptable and audit events are not
required. Do not copy patterns from `internal-tools/` into `payments/` or
`lending/`.

## Tests

Behaviour changes come with JUnit 5 tests. Tests assert on the sealed result and,
where the behaviour is audit related, on the audit event that was written.
