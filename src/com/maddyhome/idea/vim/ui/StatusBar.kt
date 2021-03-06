/*
 * IdeaVim - Vim emulator for IDEs based on the IntelliJ platform
 * Copyright (C) 2003-2020 The IdeaVim authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.maddyhome.idea.vim.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.DataManager
import com.intellij.ide.lightEdit.LightEditCompatible
import com.intellij.ide.plugins.InstalledPluginsState
import com.intellij.ide.plugins.PluginManager
import com.intellij.ide.plugins.PluginManagerMain
import com.intellij.ide.plugins.RepositoryHelper
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.PerformInBackgroundOption
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.updateSettings.impl.PluginDownloader
import com.intellij.openapi.updateSettings.impl.UpdateChecker
import com.intellij.openapi.updateSettings.impl.UpdateInstaller
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.intellij.openapi.util.Ref
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import com.intellij.util.text.VersionComparatorUtil
import com.maddyhome.idea.vim.VimPlugin
import com.maddyhome.idea.vim.group.NotificationService
import com.maddyhome.idea.vim.option.IdeaStatusIcon
import com.maddyhome.idea.vim.option.OptionsManager
import icons.VimIcons
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.SwingConstants

const val STATUS_BAR_ICON_ID = "IdeaVim-Icon"
const val STATUS_BAR_DISPLAY_NAME = "IdeaVim"

class StatusBarIconFactory : StatusBarWidgetFactory, LightEditCompatible {

  override fun getId(): String = STATUS_BAR_ICON_ID

  override fun getDisplayName(): String = STATUS_BAR_DISPLAY_NAME

  override fun disposeWidget(widget: StatusBarWidget) {}

  override fun isAvailable(project: Project): Boolean {
    @Suppress("DEPRECATION")
    if (!OptionsManager.ideastatusbar.isSet) return false
    if (OptionsManager.ideastatusicon.value == IdeaStatusIcon.disabled) return false
    return true
  }

  override fun createWidget(project: Project): StatusBarWidget {
    OptionsManager.ideastatusicon.addOptionChangeListener { _, _ -> updateAll() }
    return VimStatusBar()
  }

  override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

  override fun isConfigurable(): Boolean = false

  private fun updateAll() {
    val projectManager = ProjectManager.getInstanceIfCreated() ?: return
    for (project in projectManager.openProjects) {
      val statusBarWidgetsManager = project.getService(StatusBarWidgetsManager::class.java) ?: continue
      statusBarWidgetsManager.updateWidget(this)
    }

    updateIcon()
  }

  companion object {
    fun updateIcon() {
      val projectManager = ProjectManager.getInstanceIfCreated() ?: return
      for (project in projectManager.openProjects) {
        val statusBar = WindowManager.getInstance().getStatusBar(project) ?: continue
        statusBar.updateWidget(STATUS_BAR_ICON_ID)
      }
    }
  }
}

class VimStatusBar : StatusBarWidget, StatusBarWidget.IconPresentation {

  override fun ID(): String = STATUS_BAR_ICON_ID

  override fun install(statusBar: StatusBar) {}

  override fun dispose() {}

  override fun getTooltipText() = STATUS_BAR_DISPLAY_NAME

  override fun getIcon(): Icon {
    if (OptionsManager.ideastatusicon.value == IdeaStatusIcon.gray) return VimIcons.IDEAVIM_DISABLED
    return if (VimPlugin.isEnabled()) VimIcons.IDEAVIM else VimIcons.IDEAVIM_DISABLED
  }

  override fun getClickConsumer() = Consumer<MouseEvent> { event ->
    val component = event.component
    val popup = VimActionsPopup.getPopup(DataManager.getInstance().getDataContext(component))
    val dimension = popup.content.preferredSize

    val at = Point(0, -dimension.height)
    popup.show(RelativePoint(component, at))
  }

  override fun getPresentation(): StatusBarWidget.WidgetPresentation? = this
}

class VimActions : DumbAwareAction() {

  companion object {
    const val actionPlace = "VimActionsPopup"
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    VimActionsPopup.getPopup(e.dataContext).showCenteredInCurrentWindow(project)
  }

  override fun update(e: AnActionEvent) {
    val project = e.project
    e.presentation.isEnabledAndVisible = project != null && !project.isDisposed
  }
}

private object VimActionsPopup {
  fun getPopup(dataContext: DataContext): ListPopup {
    val actions = getActions()
    val popup = JBPopupFactory.getInstance()
      .createActionGroupPopup(STATUS_BAR_DISPLAY_NAME, actions,
        dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false,
        VimActions.actionPlace)
    popup.setAdText("Version ${VimPlugin.getVersion()}", SwingConstants.CENTER)

    return popup
  }

  private fun getActions(): DefaultActionGroup {
    val actionGroup = DefaultActionGroup()
    actionGroup.isPopup = true

    actionGroup.add(ActionManager.getInstance().getAction("VimPluginToggle"))
    actionGroup.addSeparator()
    actionGroup.add(NotificationService.OpenIdeaVimRcAction(null))
    actionGroup.add(ShortcutConflictsSettings)
    actionGroup.addSeparator()

    val eapGroup = DefaultActionGroup("EAP" + if (JoinEap.eapActive()) " (Active)" else "", true)
    eapGroup.add(JoinEap)
    eapGroup.add(HelpLink("About EAP...", "https://github.com/JetBrains/ideavim#get-early-access", null))
    actionGroup.add(eapGroup)

    val helpGroup = DefaultActionGroup("Contacts && Help", true)
    helpGroup.add(HelpLink("Contact on Twitter", "https://twitter.com/ideavim", VimIcons.TWITTER))
    helpGroup.add(HelpLink("Create an Issue", "https://youtrack.jetbrains.com/issues/VIM", VimIcons.YOUTRACK))
    helpGroup.add(HelpLink("Contribute on GitHub", "https://github.com/JetBrains/ideavim", VimIcons.GITHUB))
    actionGroup.add(helpGroup)

    return actionGroup
  }
}

private class HelpLink(
  name: String,
  val link: String,
  icon: Icon?
) : DumbAwareAction(name, null, icon) {
  override fun actionPerformed(e: AnActionEvent) {
    BrowserUtil.browse(link)
  }
}

private object ShortcutConflictsSettings : DumbAwareAction("Settings...") {
  override fun actionPerformed(e: AnActionEvent) {
    ShowSettingsUtil.getInstance().editConfigurable(e.project, VimEmulationConfigurable())
  }
}

private object JoinEap : DumbAwareAction() {
  private const val EAP_LINK = "https://plugins.jetbrains.com/plugins/eap/ideavim"

  fun eapActive() = EAP_LINK in UpdateSettings.getInstance().storedPluginHosts

  override fun actionPerformed(e: AnActionEvent) {
    if (eapActive()) {
      UpdateSettings.getInstance().storedPluginHosts -= EAP_LINK
      VimPlugin.getNotifications(e.project).notifyEapFinished()
    } else {
      UpdateSettings.getInstance().storedPluginHosts += EAP_LINK
      checkForUpdates(e.project)
    }
  }

  override fun update(e: AnActionEvent) {
    if (eapActive()) {
      e.presentation.text = "Finish EAP"
    } else {
      e.presentation.text = "Get Early Access..."
    }
  }

  private fun checkForUpdates(project: Project?) {
    val notificator = VimPlugin.getNotifications(project)

    val pluginRef = Ref.create<PluginDownloader>()

    object : Task.Backgroundable(null, "Checking for IdeaVim EAP version", true) {
      override fun run(indicator: ProgressIndicator) {
        val downloaders = mutableListOf<PluginDownloader>()
        val build = ApplicationInfo.getInstance().build
        for (host in RepositoryHelper.getPluginHosts()) {
          val newPluginDescriptor = RepositoryHelper
            .loadPlugins(host, null, indicator)
            .filter { it.pluginId == VimPlugin.getPluginId() }
            .maxWith(java.util.Comparator { o1, o2 -> VersionComparatorUtil.compare(o1.version, o2.version) })
            ?: continue

          downloaders += PluginDownloader.createDownloader(newPluginDescriptor, host, build)
        }
        val plugin = downloaders.maxWith(java.util.Comparator { o1, o2 -> VersionComparatorUtil.compare(o1.pluginVersion, o2.pluginVersion) })
        pluginRef.set(plugin)
      }

      override fun onSuccess() {
        val downloader: PluginDownloader = pluginRef.get() ?: run {
          notificator.notifySubscribedToEap()
          return
        }
        val currentVersion = PluginManager.getPlugin(VimPlugin.getPluginId())?.version ?: ""
        if (VersionComparatorUtil.compare(downloader.pluginVersion, currentVersion) <= 0) {
          notificator.notifySubscribedToEap()
          return
        }

        val version = downloader.pluginVersion
        val message = "Do you want to install the EAP version of IdeaVim?"

        @Suppress("MoveVariableDeclarationIntoWhen")
        val res = Messages.showYesNoCancelDialog(project, message, "IdeaVim $version", null)
        when (res) {
          Messages.YES -> updatePlugin(project, downloader)
          Messages.NO -> notificator.notifySubscribedToEap()
          Messages.CANCEL -> if (eapActive()) UpdateSettings.getInstance().storedPluginHosts -= EAP_LINK
        }
      }

      override fun onCancel() {
        notificator.notifySubscribedToEap()
      }

      override fun onThrowable(error: Throwable) {
        notificator.notifySubscribedToEap()
      }
    }.queue()
  }

  private fun updatePlugin(project: Project?, downloader: PluginDownloader) {
    val notificator = VimPlugin.getNotifications(project)
    return object : Task.Backgroundable(null, "Plugin Updates", true, PerformInBackgroundOption.DEAF) {
      private var updated = false
      override fun run(indicator: ProgressIndicator) {
        val state = InstalledPluginsState.getInstance()
        state.onDescriptorDownload(downloader.descriptor)
        UpdateChecker.checkAndPrepareToInstall(downloader, state, mutableMapOf(VimPlugin.getPluginId() to downloader), mutableListOf(), indicator)
        updated = UpdateInstaller.installPluginUpdates(listOf(downloader), indicator)
      }

      override fun onSuccess() {
        if (updated) {
          PluginManagerMain.notifyPluginsUpdated(null)
        } else {
          notificator.notifyFailedToDownloadEap()
        }
      }

      override fun onCancel() {
        notificator.notifyFailedToDownloadEap()
      }

      override fun onThrowable(error: Throwable) {
        notificator.notifyFailedToDownloadEap()
      }
    }.queue()
  }
}
