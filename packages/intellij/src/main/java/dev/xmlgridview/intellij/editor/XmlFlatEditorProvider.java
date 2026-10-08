package dev.xmlgridview.intellij.editor;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileTypes.FileTypeRegistry;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Adds a "Flat" editor tab after the Grid tab for XML files, so the editor shows
 * bottom tabs <b>Text | Grid | Flat</b>. Ordering relative to Grid comes from
 * the {@code order} attribute in plugin.xml.
 */
public final class XmlFlatEditorProvider implements FileEditorProvider, DumbAware {
  public static final String EDITOR_TYPE_ID = "xml-grid-view-flat";

  @Override
  public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
    return !file.isDirectory() && FileTypeRegistry.getInstance().isFileOfType(file, XmlFileType.INSTANCE);
  }

  @Override
  public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
    return new XmlFlatViewerEditor(project, file);
  }

  @Override
  public @NotNull String getEditorTypeId() {
    return EDITOR_TYPE_ID;
  }

  @Override
  public @NotNull FileEditorPolicy getPolicy() {
    return FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR;
  }
}
