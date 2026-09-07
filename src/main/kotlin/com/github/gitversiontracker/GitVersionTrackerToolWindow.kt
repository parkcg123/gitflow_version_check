/*
 * Copyright 2026 Git Branch Version Tracker Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.gitversiontracker

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * Data representation of an extracted Git branch merge version item.
 */
data class GitVersionItem(
    val type: String,
    val version: String,
    val date: String,
    val commitHash: String,
    val rawSubject: String
)

/**
 * Non-editable table model for displaying Git version records.
 */
class GitVersionTableModel : DefaultTableModel(
    arrayOf("Type", "Version", "Date", "Commit Hash", "Raw Subject"), 0
) {
    override fun isCellEditable(row: Int, column: Int): Boolean = false
    override fun getColumnClass(columnIndex: Int): Class<*> = String::class.java
}

/**
 * ToolWindow factory registering the Git Branch Version Tracker UI.
 */
class GitVersionTrackerToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GitVersionTrackerPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
        Disposer.register(toolWindow.disposable, panel)
    }
}

/**
 * Main UI panel containing toolbar controls and a table displaying merge commit versions.
 */
class GitVersionTrackerPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {

    private val tableModel = GitVersionTableModel()
    private val table = JBTable(tableModel)

    private val limitCombo = ComboBox(arrayOf(500, 1000))
    private val typeFilterCombo = ComboBox(arrayOf("ALL", "hotfix", "release", "feature"))
    private val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh)
    private val copyButton = JButton("Copy Version", AllIcons.Actions.Copy)
    private val statusLabel = JBLabel("Ready")

    private var allParsedItems: List<GitVersionItem> = emptyList()

    companion object {
        // Regex matching merge commits for hotfix, release, and feature branches
        // Supports both local merge syntax and remote-tracking branch prefixes
        private val MERGE_REGEX = Regex("""Merge (?:remote-tracking )?branch '(?:[^/]+/)?(hotfix|release|feature)/([^']+)'""")
        private const val NOTIFICATION_GROUP_ID = "Git Version Tracker Notifications"
    }

    init {
        setupUI()
        registerListeners()
        loadGitVersions()
    }

    private fun setupUI() {
        // Top Toolbar
        val topToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        topToolbar.add(JBLabel("Limit:"))
        topToolbar.add(limitCombo)
        topToolbar.add(JBLabel("Type:"))
        topToolbar.add(typeFilterCombo)
        topToolbar.add(refreshButton)
        topToolbar.add(copyButton)

        // Table configurations
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.fillsViewportHeight = true
        table.autoCreateRowSorter = true

        table.columnModel.getColumn(0).preferredWidth = 80   // Type
        table.columnModel.getColumn(1).preferredWidth = 160  // Version
        table.columnModel.getColumn(2).preferredWidth = 100  // Date
        table.columnModel.getColumn(3).preferredWidth = 90   // Commit Hash
        table.columnModel.getColumn(4).preferredWidth = 350  // Raw Subject

        val scrollPane = JBScrollPane(table)

        // Bottom status panel
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2))
        bottomPanel.add(statusLabel)

        add(topToolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun registerListeners() {
        refreshButton.addActionListener {
            loadGitVersions()
        }

        limitCombo.addActionListener {
            loadGitVersions()
        }

        typeFilterCombo.addActionListener {
            applyFilter()
        }

        copyButton.addActionListener {
            copySelectedVersion()
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    copySelectedVersion()
                }
            }
        })
    }

    /**
     * Executes Git command in a background thread and parses merge commits.
     */
    fun loadGitVersions() {
        val selectedLimit = limitCombo.selectedItem as? Int ?: 500
        val repositoryManager = GitRepositoryManager.getInstance(project)
        val repositories = repositoryManager.repositories

        if (repositories.isEmpty()) {
            statusLabel.text = "No Git repository found in project."
            tableModel.rowCount = 0
            return
        }

        statusLabel.text = "Loading Git merge commits..."
        refreshButton.isEnabled = false

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Loading Git Version History...", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Querying Git merge logs (limit: $selectedLimit)..."

                val parsedItems = mutableListOf<GitVersionItem>()

                for (repo in repositories) {
                    if (indicator.isCanceled) break

                    val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
                    handler.addParameters(
                        "-n", selectedLimit.toString(),
                        "--merges",
                        "--pretty=format:%h%x09%cs%x09%s"
                    )

                    val result = Git.getInstance().runCommand(handler)
                    if (result.success()) {
                        for (line in result.output) {
                            val parts = line.split('\t', limit = 3)
                            if (parts.size >= 3) {
                                val commitHash = parts[0].trim()
                                val commitDate = parts[1].trim()
                                val subject = parts[2].trim()

                                val matchResult = MERGE_REGEX.find(subject)
                                if (matchResult != null) {
                                    val branchType = matchResult.groupValues[1]
                                    val versionString = matchResult.groupValues[2]

                                    parsedItems.add(
                                        GitVersionItem(
                                            type = branchType,
                                            version = versionString,
                                            date = commitDate,
                                            commitHash = commitHash,
                                            rawSubject = subject
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Update UI on EDT
                ApplicationManager.getApplication().invokeLater {
                    allParsedItems = parsedItems
                    refreshButton.isEnabled = true
                    applyFilter()
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    refreshButton.isEnabled = true
                    statusLabel.text = "Log query cancelled."
                }
            }

            override fun onThrowable(error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    refreshButton.isEnabled = true
                    statusLabel.text = "Error: ${error.message}"
                }
            }
        })
    }

    /**
     * Filters in-memory parsed items based on the selected branch type and updates table.
     */
    private fun applyFilter() {
        val filterType = typeFilterCombo.selectedItem as? String ?: "ALL"
        val filtered = if (filterType.equals("ALL", ignoreCase = true)) {
            allParsedItems
        } else {
            allParsedItems.filter { it.type.equals(filterType, ignoreCase = true) }
        }

        tableModel.rowCount = 0
        for (item in filtered) {
            tableModel.addRow(arrayOf(
                item.type,
                item.version,
                item.date,
                item.commitHash,
                item.rawSubject
            ))
        }

        statusLabel.text = "Showing ${filtered.size} of ${allParsedItems.size} versions (Filter: $filterType)"
    }

    /**
     * Copies the version string of the selected table row to the system clipboard.
     */
    private fun copySelectedVersion() {
        val selectedRow = table.selectedRow
        if (selectedRow == -1) {
            notifyMessage("Please select a row to copy.", NotificationType.WARNING)
            return
        }

        val modelRow = table.convertRowIndexToModel(selectedRow)
        val version = tableModel.getValueAt(modelRow, 1) as? String

        if (!version.isNullOrEmpty()) {
            CopyPasteManager.getInstance().setContents(StringSelection(version))
            notifyMessage("Copied '$version' to clipboard.", NotificationType.INFORMATION)
        }
    }

    /**
     * Displays a notification balloon to the user.
     */
    private fun notifyMessage(content: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                ?.createNotification(content, type)
                ?.notify(project)
        } catch (_: Exception) {
            statusLabel.text = content
        }
    }

    override fun dispose() {
        allParsedItems = emptyList()
    }
}
