# Legacy Metadata Producer Lanes

The non-aggregated `legacyMetadataProducer384` and
`legacyMetadataProducer338` sbt projects compile the frozen source under
`legacy-metadata-marker-fixture/src/main/scala` with Scala 3.8.4 and 3.3.8,
respectively. Their package filters remove the obsolete Scala
`paradise3.api.expander` carrier before any current consumer sees the marker
artifact.

These projects are compatibility probes under the repository's required JDK
25. They do not change the pinned root compiler or establish full
ResearchPlugin support on either stable Scala version.
