package dev.xmlgridview.intellij.model;

/** Which fields a search looks at. */
public record SearchTargets(boolean names, boolean attrNames, boolean attrValues, boolean text) {
  public static final SearchTargets ALL = new SearchTargets(true, true, true, true);

  /** Parses a fixture target name: all, names, attrNames, attrValues or text. */
  public static SearchTargets of(String name) {
    return switch (name) {
      case "all" -> ALL;
      case "names" -> new SearchTargets(true, false, false, false);
      case "attrNames" -> new SearchTargets(false, true, false, false);
      case "attrValues" -> new SearchTargets(false, false, true, false);
      case "text" -> new SearchTargets(false, false, false, true);
      default -> throw new IllegalArgumentException("Unknown target " + name);
    };
  }
}
