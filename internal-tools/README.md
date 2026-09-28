# internal-tools

Operator utilities. Run by hand from a bastion host, never deployed as part of a
service and never on a customer request path.

Standards here are looser than `payments/` and `lending/`: printing to stdout is
fine because an operator is reading the output live, and audit events are not
required because these tools do not move money. Anything in here that starts
moving money belongs in `payments/` instead, under the rules that apply there.

## Backfill tool

Recomputes the reporting summary for a date range after a ledger correction.

```bash
./gradlew :internal-tools:run --args="2025-02-01 2025-02-28"
```

Pass `--csv` when finance asks for something they can paste into a spreadsheet:

```bash
./gradlew :internal-tools:run --args="2025-02-01 2025-02-28 --csv"
```
