# Security policy

Macro-Paradise for Scala 3 is experimental compiler-plugin research. It loads
precompiled handler code into a compiler process and exposes compiler-internal
tree APIs. It has not received a production security review, and no released
version is currently supported.

## Reporting limitation

No private security-reporting channel is currently offered or promised.
Non-sensitive security questions may use the repository's ordinary issue
tracker when it is available.

Do not place secrets, exploit details, sensitive source, private repository
data, or credentials in a public issue merely to obtain attention. A private
reporting mechanism may be added later; this document does not invent an email
address, form, response service, or confidentiality promise.

## Useful report context

When it is safe to share, identify the JDK and exact Scala compiler version,
the affected plugin or handler boundary, the configuration used, and a minimal
reproduction. Distinguish untrusted handler behavior from a failure in the
plugin's loading, validation, isolation, or rollback logic.

No response-time, embargo, maintenance, or security-support SLA is promised.
See [Versioning and stability](docs/VERSIONING_AND_STABILITY.md).
