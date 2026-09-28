# circular-payments

Kotlin services behind Circular's money movement: card payments, transfers,
refunds, loan disbursement and repayment.

## Modules

| Module           | What lives there                                                        |
| ---------------- | ----------------------------------------------------------------------- |
| `payments`       | Payment intake, transfers, refunds, the `Money` type, audit and logging  |
| `lending`        | Loan disbursement and repayment, built on the `payments` primitives      |
| `internal-tools` | Operator scripts and backfills. Not customer facing, looser standards    |

## Running locally

```bash
./gradlew :payments:bootRun
./gradlew test
```

## The payment intake contract

`POST /payments` requires an `Idempotency-Key` header. Callers generate one key
per logical payment attempt and reuse it on retries. The service stores the
result against the key, so a retry with the same key returns the original
response instead of charging the customer twice. A request without the header is
rejected with `400`, since we cannot make it safe to retry.

Every client, including the web checkout in `circular-web`, is expected to send
the header on every attempt.

## Conventions

Amounts are `Money`, never `Double`. Services return sealed result types.
Money movement writes an audit event. See `.cursorrules` and
`docs/sox-controls.md` for the details auditors ask about.
