# Support

This project is experimental and currently has no service-level, response-time,
release-cadence, or long-term maintenance commitment.

Before opening a non-sensitive issue, include:

- JDK vendor and feature version;
- exact Scala compiler version;
- sbt version;
- the command that failed;
- whether the failure is in the built-in plugin path, external-handler
  precheck, handler loading, consumer compilation, or runtime;
- a minimal reproduction and the complete diagnostic without credentials or
  sensitive source.

Use the exact source checkout and documented commands. No remotely published
artifact or stable coordinate is currently supported. Check
[Compatibility](docs/COMPATIBILITY.md),
[Diagnostics](docs/DIAGNOSTICS.md), and
[Supported scope and limitations](docs/SUPPORTED_SCOPE_AND_LIMITATIONS.md)
before reporting an unsupported shape as a defect.

Security-sensitive material must not be posted publicly. The current lack of a
private reporting channel is documented in [Security](SECURITY.md).
