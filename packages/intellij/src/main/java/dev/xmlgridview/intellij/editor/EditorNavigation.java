package dev.xmlgridview.intellij.editor;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Navigation shared by the Grid and Flat tabs. */
final class EditorNavigation {
  private EditorNavigation() {
  }

  /**
   * Switches to the Text tab, moves the caret to {@code offset}, centers it and focuses the editor.
   * Returns the text editor, or null if it could not be opened.
   */
  static @Nullable Editor navigate(@NotNull Project project, @NotNull VirtualFile file, int offset) {
    Editor editor = FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project, file, offset), true);
    if (editor == null) return null;
    int target = Math.max(0, Math.min(offset, editor.getDocument().getTextLength()));
    editor.getSelectionModel().removeSelection();
    editor.getCaretModel().moveToOffset(target);
    editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
    IdeFocusManager.getGlobalInstance().requestFocus(editor.getContentComponent(), true);
    return editor;
  }
}
