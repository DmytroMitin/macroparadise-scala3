# User onboarding three-mode fixture

This product-owned template retains the exact `macro-annotations/`,
`macro-handlers/`, and `core/` source topology used by the external onboarding
matrix.

- `manual/build.sbt` uses explicit project locations and the public
  `ExternalArtifactIdentity` build helper copied by the verifier.
- `local-project/build.sbt` uses the source-built sbt integration with static
  local project references and no producer `publishLocal`.
- `published-module/build.sbt` publishes exact-full-cross producer artifacts to
  a task-owned repository and resolves them through the integration keys.

The template is copied to a disposable directory by
`verifyUserOnboardingThreeModeSetup`; it is not intended to resolve the
unreleased snapshot coordinates directly from a remote repository.
