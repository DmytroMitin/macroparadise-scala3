package paradise3;

import paradise3.api.expander;

@expander("demo.ExternalDebugExpander")
public final class MetadataInitializationProbe {
  public static final String PROPERTY = "macroparadise.metadataInitializationProbe";

  static {
    System.setProperty(PROPERTY, "initialized");
  }

  private MetadataInitializationProbe() {}
}
