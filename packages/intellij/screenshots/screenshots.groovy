// IDE startup script used by `./gradlew runIdeForScreenshots`: opens the sample
// project's books.xml, shows the Grid and Flat tabs, paints the IDE window into
// PNG files (no screen-recording permission needed) and exits.
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.table.JBTable
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil

import javax.imageio.ImageIO
import java.awt.image.BufferedImage

def project = IDE.project
def outDir = new File(System.getProperty("xgv.shots.out"))
def xmlPath = System.getProperty("xgv.shots.file")
outDir.mkdirs()

Thread.start {
  def edt = { Closure c -> ApplicationManager.application.invokeAndWait(c) }
  def file = null
  try {
    sleep(10000)
    edt {
      def frame = WindowManager.instance.getFrame(project)
      frame.extendedState = 0
      frame.setBounds(0, 0, 1600, 1000)
      file = LocalFileSystem.instance.refreshAndFindFileByPath(xmlPath)
      def fem = FileEditorManager.getInstance(project)
      fem.openFile(file, true)
      fem.openFiles.findAll { it != file }.each { fem.closeFile(it) }
      ToolWindowManager.getInstance(project).getToolWindow("Project")?.hide()
    }
    sleep(4000)
    def shot = { String name, String editorTypeId, Closure prepare ->
      edt { FileEditorManager.getInstance(project).setSelectedEditor(file, editorTypeId) }
      sleep(5000)
      edt {
        def editor = FileEditorManager.getInstance(project).getSelectedEditor(file)
        prepare(editor.component)
        NotificationsManager.notificationsManager.getNotificationsOfType(Notification, project).each { it.expire() }
      }
      sleep(1500)
      edt {
        def root = WindowManager.instance.getFrame(project).rootPane
        def img = new BufferedImage(root.width * 2, root.height * 2, BufferedImage.TYPE_INT_RGB)
        def g = img.createGraphics()
        g.scale(2, 2)
        root.paint(g)
        g.dispose()
        ImageIO.write(img, "png", new File(outDir, name + ".png"))
      }
    }
    shot("intellij-grid", "xml-grid-view") { c ->
      def tree = UIUtil.findComponentsOfType(c, Tree).find { it.showing }
      tree?.expandRow(3)
      def table = UIUtil.findComponentsOfType(c, JBTable).find { it.showing && it.rowCount > 3 }
      if (table) {
        table.setRowSelectionInterval(3, 4)
        table.setColumnSelectionInterval(1, 3)
        table.requestFocusInWindow()
      }
    }
    shot("intellij-flat", "xml-grid-view-flat") { c ->
      def table = UIUtil.findComponentsOfType(c, JBTable).find { it.showing && it.rowCount > 6 }
      if (table) {
        table.setRowSelectionInterval(5, 5)
        table.requestFocusInWindow()
      }
    }
    // Value inspector on a JSON value (plugin classes come from the plugin's class loader).
    def cl = com.intellij.ide.plugins.PluginManagerCore.getPlugin(
      com.intellij.openapi.extensions.PluginId.getId("io.github.bdarwin.xmlgridview")).pluginClassLoader
    def targetCls = cl.loadClass("dev.xmlgridview.intellij.model.InspectTarget")
    def inspectorCls = cl.loadClass("dev.xmlgridview.intellij.ui.ValueInspector")
    def json = new File(new File(xmlPath).parentFile, "metadata.json").text
    edt {
      def target = targetCls.getConstructor(String, String).newInstance("catalog › book[1] › metadata", json)
      inspectorCls.getMethod("show", com.intellij.openapi.project.Project, targetCls).invoke(null, project, target)
    }
    sleep(3000)
    def dialogShot = { String name, int tab ->
      edt {
        def dialog = java.awt.Window.windows.find { it.showing && it instanceof javax.swing.JDialog && it.title?.startsWith("Value") }
        if (dialog == null) throw new IllegalStateException("inspector dialog not found")
        dialog.setSize(1100, 700)
        def tabs = UIUtil.findComponentsOfType(dialog.rootPane, javax.swing.JTabbedPane).find { it.showing }
        if (tabs != null && tab < tabs.tabCount) tabs.selectedIndex = tab
      }
      sleep(1500)
      edt {
        def dialog = java.awt.Window.windows.find { it.showing && it instanceof javax.swing.JDialog && it.title?.startsWith("Value") }
        def tree = UIUtil.findComponentsOfType(dialog.rootPane, Tree).find { it.showing }
        if (tree != null) for (int i = 0; i < Math.min(tree.rowCount, 12); i++) tree.expandRow(i)
        def root = dialog.rootPane
        def img = new BufferedImage(root.width * 2, root.height * 2, BufferedImage.TYPE_INT_RGB)
        def g = img.createGraphics()
        g.scale(2, 2)
        root.paint(g)
        g.dispose()
        ImageIO.write(img, "png", new File(outDir, name + ".png"))
      }
    }
    dialogShot("intellij-inspector-tree", 0)
    dialogShot("intellij-inspector-grid", 1)
    new File(outDir, "done.txt").text = "ok"
  } catch (Throwable t) {
    new File(outDir, "error.txt").text = t.toString() + "\n" + t.stackTrace.join("\n")
  } finally {
    edt { ApplicationManager.application.exit(true, true, false) }
  }
}
