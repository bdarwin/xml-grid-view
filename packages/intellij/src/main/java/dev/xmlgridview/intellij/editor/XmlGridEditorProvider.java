package dev.xmlgridview.intellij.editor;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.fileEditor.TextEditorWithPreview;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Opens XML files in a text editor with an optional XML Grid View alongside
 * (Editor / Split / Viewer toggle). The text editor is shown by default.
 */
public final class XmlGridEditorProvider implements FileEditorProvider, DumbAware {
  public static final String EDITOR_TYPE_ID = "xml-grid-view";

  @Override
  public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
    return !file.isDirectory() && FileTypeRegistry.getInstance().isFileOfType(file, XmlFileType.INSTANCE);
  }

  @Override
  public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
    TextEditor textEditor = (TextEditor)TextEditorProvider.getInstance().createEditor(project, file);
    XmlGridViewerEditor viewer = new XmlGridViewerEditor(project, file, textEditor);
    TextEditorWithPreview composite =
      new TextEditorWithPreview(textEditor, viewer, "XML Grid View", TextEditorWithPreview.Layout.SHOW_EDITOR);
    viewer.attach(composite);
    return composite;
  }

  @Override
  public @NotNull String getEditorTypeId() {
    return EDITOR_TYPE_ID;
  }

  @Override
  public @NotNull FileEditorPolicy getPolicy() {
    return FileEditorPolicy.HIDE_DEFAULT_EDITOR;
  }
}
