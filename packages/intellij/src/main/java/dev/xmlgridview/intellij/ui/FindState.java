package dev.xmlgridview.intellij.ui;

import dev.xmlgridview.intellij.model.GridMatch;
import dev.xmlgridview.intellij.model.Matcher;
import dev.xmlgridview.intellij.model.SearchTargets;
import dev.xmlgridview.intellij.model.XNode;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Set;

/**
 * Current find results, read by the tree and grid renderers on the EDT.
 * Replaced as a whole (immutable) whenever a search completes.
 */
public record FindState(@Nullable Matcher matcher,
                        SearchTargets targets,
                        Set<XNode> matchedNodes,
                        Set<GridMatch> gridCells,
                        @Nullable XNode currentNode,
                        @Nullable GridMatch currentCell) {
  public static final FindState NONE =
    new FindState(null, SearchTargets.ALL, Collections.emptySet(), Collections.emptySet(), null, null);

  public boolean active() {
    return matcher != null || !matchedNodes.isEmpty();
  }

  public FindState withCurrent(@Nullable XNode node, @Nullable GridMatch cell) {
    return new FindState(matcher, targets, matchedNodes, gridCells, node, cell);
  }
}
