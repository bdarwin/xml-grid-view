package dev.xmlgridview.intellij.model;

/** One matching field of one element. {@code attrIndex} is -1 unless the target is an attribute. */
public record DocMatch(XNode node, MatchTarget target, int attrIndex, String field) {
  /** Source offset to navigate to for this match. */
  public int offset() {
    if (attrIndex >= 0) return node.attrs().get(attrIndex).start();
    if (target == MatchTarget.TEXT && node.textStart() >= 0) return node.textStart();
    return node.start();
  }
}
