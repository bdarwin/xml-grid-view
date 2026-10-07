package dev.xmlgridview.intellij.model;

/** An attribute with its decoded value and source span (UTF-16 offsets). */
public record XAttr(String name, String value, int start, int end) {
}
