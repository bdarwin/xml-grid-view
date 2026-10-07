package dev.xmlgridview.intellij.editor;

import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.TextEditorWithPreview;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import dev.xmlgridview.intellij.ui.XmlGridPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.beans.PropertyChangeListener;

/** The viewer half of the composite editor. Read-only: it never modifies the document. */
public final class XmlGridViewerEditor extends UserDataHolderBase implements FileEditor {
  private final VirtualFile file;
  private final TextEditor textEditor;
  private final XmlGridPanel panel;
  private @Nullable TextEditorWithPreview composite;

  public XmlGridViewerEditor(@NotNull Project project, @NotNull VirtualFile file, @NotNull TextEditor textEditor) {
    this.file = file;
    this.textEditor = textEditor;
    this.panel = new XmlGridPanel(project, textEditor.getEditor().getDocument(), this::navigate, this);
  }

  void attach(@NotNull TextEditorWithPreview composite) {
    this.composite = composite;
  }

  public @NotNull XmlGridPanel getPanel() {
    return panel;
  }

  /** Moves the caret to {@code offset} in the text editor, centers it and focuses the editor. */
  public void navigate(int offset) {
    if (composite != null && composite.getLayout() == TextEditorWithPreview.Layout.SHOW_PREVIEW) {
      composite.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW);
    }
    Editor editor = textEditor.getEditor();
    int target = Math.max(0, Math.min(offset, editor.getDocument().getTextLength()));
    editor.getSelectionModel().removeSelection();
    editor.getCaretModel().moveToOffset(target);
    editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
    IdeFocusManager.getGlobalInstance().requestFocus(editor.getContentComponent(), true);
  }

  @Override
  public @NotNull JComponent getComponent() {
    return panel;
  }

  @Override
  public @Nullable JComponent getPreferredFocusedComponent() {
    return panel.getPreferredFocusedComponent();
  }

  @Override
  public @NotNull String getName() {
    return "XML Grid";
  }

  @Override
  public void setState(@NotNull FileEditorState state) {
  }

  @Override
  public boolean isModified() {
    return false;
  }

  @Override
  public boolean isValid() {
    return file.isValid();
  }

  @Override
  public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
  }

  @Override
  public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
  }

  @Override
  public @NotNull VirtualFile getFile() {
    return file;
  }

  @Override
  public void dispose() {
    Disposer.dispose(panel);
  }
}
