package dev.xmlgridview.intellij.model;

/** A well-formedness error. Line and column are 1-based; offset is a document offset. */
public record ParseError(String message, int line, int column, int offset) {
}
