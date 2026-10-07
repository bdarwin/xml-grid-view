package dev.xmlgridview.intellij.model;

/** A query that can't be compiled (invalid regular expression). */
public final class InvalidQueryException extends Exception {
  public InvalidQueryException(String message) {
    super(message);
  }
}
