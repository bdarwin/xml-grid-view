package dev.xmlgridview.intellij.editor;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import dev.xmlgridview.intellij.ui.FlatPanel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.beans.PropertyChangeListener;

/** The "Flat" tab: an outline sheet of the whole document. Read-only: it never modifies the document. */
public final class XmlFlatViewerEditor extends UserDataHolderBase implements FileEditor {
  private final Project project;
  private final VirtualFile file;
  private final FlatPanel panel;

  public XmlFlatViewerEditor(@NotNull Project project, @NotNull VirtualFile file) {
    this.project = project;
    this.file = file;
    Document document = FileDocumentManager.getInstance().getDocument(file);
    if (document == null) throw new IllegalArgumentException("No document for " + file);
    this.panel = new FlatPanel(project, document, this::navigate, this);
  }

  public @NotNull FlatPanel getPanel() {
    return panel;
  }

  /** Switches to the Text tab with the caret at {@code offset}; returns the text editor or null. */
  public @Nullable Editor navigate(int offset) {
    return EditorNavigation.navigate(project, file, offset);
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
    return "Flat";
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
