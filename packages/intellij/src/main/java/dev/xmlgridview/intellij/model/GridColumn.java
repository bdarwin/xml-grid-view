package dev.xmlgridview.intellij.model;

/** A grid column. Key is "@name" for attributes, the tag for child elements, or "#text". */
public record GridColumn(String key, String label, Kind kind) {
  public enum Kind {
    ATTR("attr"), LEAF("leaf"), COMPLEX("complex"), TEXT("text");

    private final String id;

    Kind(String id) { this.id = id; }

    /** Canonical lowercase name used in fixtures. */
    public String id() { return id; }
  }

  public static final String TEXT_KEY = "#text";
}
