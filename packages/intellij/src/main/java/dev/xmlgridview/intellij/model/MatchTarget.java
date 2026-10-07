package dev.xmlgridview.intellij.model;

/** The field a document match was found in. */
public enum MatchTarget {
  NAME("name"), ATTR_NAME("attrName"), ATTR_VALUE("attrValue"), TEXT("text");

  private final String id;

  MatchTarget(String id) { this.id = id; }

  public String id() { return id; }
}
