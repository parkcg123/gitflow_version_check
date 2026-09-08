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
import com.intellij.ide.DataManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Data representation of a Git branch item (either active/in-progress or merged/finished).
 */
data class GitVersionItem(
    val type: String,
    val version: String,
    val date: String,
    val commitHash: String,
    val rawSubject: String,
    val isFinished: Boolean = true,
    val isCurrent: Boolean = false,
    val fullBranchName: String = if (type.isNotBlank()) "$type/$version" else version
) {
    val statusText: String
        get() = when {
            isCurrent -> "Current 🌿"
            !isFinished -> "Active ⚡"
            else -> "Merged 🏷️"
        }
}

/**
 * Helper for Semantic Versioning bump calculations and next version suggestions.
 */
object SemVerHelper {
    data class NextVersionSuggestion(
        val nextHotfix: String,
        val nextMinorRelease: String,
        val nextMajorRelease: String,
        val baseVersion: String? = null
    )

    data class ParsedSemVer(
        val raw: String,
        val prefix: String,
        val major: Int,
        val minor: Int,
        val patch: Int,
        val suffix: String = "",
        val isNumericOnly: Boolean = false
    ) : Comparable<ParsedSemVer> {
        override fun compareTo(other: ParsedSemVer): Int {
            if (this.major != other.major) return this.major.compareTo(other.major)
            if (this.minor != other.minor) return this.minor.compareTo(other.minor)
            if (this.patch != other.patch) return this.patch.compareTo(other.patch)
            return this.suffix.compareTo(other.suffix)
        }
    }

    private val SEMVER_REGEX = Regex("""^(v|V)?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9a-zA-Z._]+))?$""")
    private val PURE_NUMBER_REGEX = Regex("""^(v|V)?(\d+)$""")

    fun parseVersion(versionStr: String?): ParsedSemVer? {
        if (versionStr.isNullOrBlank()) return null
        val trimmed = versionStr.trim()
        val match = SEMVER_REGEX.find(trimmed)
        if (match != null) {
            val prefix = match.groupValues[1]
            val major = match.groupValues[2].toIntOrNull() ?: 0
            val minor = match.groupValues[3].toIntOrNull() ?: 0
            val patch = match.groupValues[4].toIntOrNull() ?: 0
            val suffix = match.groupValues[5]
            return ParsedSemVer(trimmed, prefix, major, minor, patch, suffix)
        }
        val numMatch = PURE_NUMBER_REGEX.find(trimmed)
        if (numMatch != null) {
            val prefix = numMatch.groupValues[1]
            val num = numMatch.groupValues[2].toIntOrNull() ?: 0
            return ParsedSemVer(trimmed, prefix, num, 0, 0, "", isNumericOnly = true)
        }
        return null
    }

    /**
     * Finds the highest/latest semantic version among BOTH active working branches and merged commit history.
     */
    fun findHighestVersion(activeItems: Collection<GitVersionItem>, mergedItems: Collection<GitVersionItem>): String? {
        val allCandidates = activeItems + mergedItems
        return findHighestVersionFromItems(allCandidates)
    }

    /**
     * Inspects a collection of GitVersionItems (which contains both active branches and merged commits)
     * and returns the highest Semantic Version found.
     */
    fun findHighestVersionFromItems(items: Collection<GitVersionItem>): String? {
        val parsedList = items.mapNotNull { item ->
            parseVersion(item.version)
        }
        if (parsedList.isNotEmpty()) {
            return parsedList.maxOrNull()?.raw
        }
        // Fallback if no semver pattern matched
        return items.firstOrNull { it.version.isNotBlank() }?.version
    }

    fun suggestNext(latestVersion: String?): NextVersionSuggestion {
        val todayStr = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        if (latestVersion.isNullOrBlank()) {
            return NextVersionSuggestion(
                nextHotfix = "1.0.1-$todayStr",
                nextMinorRelease = "1.1.0",
                nextMajorRelease = "2.0.0",
                baseVersion = null
            )
        }

        val parsed = parseVersion(latestVersion)
        if (parsed != null && !parsed.isNumericOnly) {
            val prefix = parsed.prefix
            val major = parsed.major
            val minor = parsed.minor
            val patch = parsed.patch
            val hasDateSuffix = parsed.suffix.length == 8 && parsed.suffix.all { it.isDigit() }

            val hotfixSuffix = if (hasDateSuffix) "-$todayStr" else ""
            val nextHotfix = "$prefix$major.$minor.${patch + 1}$hotfixSuffix"
            val nextMinor = "$prefix$major.${minor + 1}.0"
            val nextMajor = "$prefix${major + 1}.0.0"

            return NextVersionSuggestion(nextHotfix, nextMinor, nextMajor, latestVersion)
        }

        val trailingDigitsRegex = Regex("""^(.*?)(\d+)$""")
        val trailingMatch = trailingDigitsRegex.find(latestVersion.trim())
        if (trailingMatch != null) {
            val prefix = trailingMatch.groupValues[1]
            val number = trailingMatch.groupValues[2].toLongOrNull() ?: 1
            val incremented = "$prefix${number + 1}"
            return NextVersionSuggestion(incremented, incremented, incremented, latestVersion)
        }

        return NextVersionSuggestion("$latestVersion-patch", "$latestVersion-minor", "$latestVersion-major", latestVersion)
    }
}

/**
 * Persistent settings for storing custom regex patterns, GitFlow base branches, and prefixes.
 */
@State(
    name = "GitVersionTrackerSettings",
    storages = [Storage("GitVersionTrackerSettings.xml")]
)
class GitVersionTrackerSettings : PersistentStateComponent<GitVersionTrackerSettings.State> {

    data class State(
        var isInitialized: Boolean = false,
        var pattern: String = DEFAULT_PATTERN,
        var productionBranch: String = "main",
        var developBranch: String = "develop",
        var featurePrefix: String = "feature/",
        var releasePrefix: String = "release/",
        var hotfixPrefix: String = "hotfix/",
        var tagPrefix: String = "v"
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        const val DEFAULT_PATTERN = """Merge (?:remote-tracking )?branch '(?:[^/]+/)?(hotfix|release|feature)/([^']+)'"""

        fun getInstance(project: Project): GitVersionTrackerSettings {
            return project.getService(GitVersionTrackerSettings::class.java)
                ?: GitVersionTrackerSettings()
        }
    }
}

/**
 * Helper object executing GitFlow operations (Init, Start, Finish, Pull, Checkout) safely.
 */
object GitFlowHelper {
    private const val NOTIFICATION_GROUP_ID = "Git Version Tracker Notifications"

    fun notifyMessage(project: Project, content: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP_ID)
                ?.createNotification(content, type)
                ?.notify(project)
        } catch (_: Exception) {
            // fallback
        }
    }

    fun getPrimaryRepo(project: Project): GitRepository? {
        return GitRepositoryManager.getInstance(project).repositories.firstOrNull()
    }

    fun getFetchHeadTimestamp(project: Project): Long {
        val repo = getPrimaryRepo(project) ?: return -1L
        val fetchHead = File(repo.root.path, ".git/FETCH_HEAD")
        return if (fetchHead.exists()) fetchHead.lastModified() else 0L
    }

    fun buildRegexPattern(hotfixPrefix: String, releasePrefix: String, featurePrefix: String): String {
        val h = Regex.escape(hotfixPrefix.trim().trimEnd('/'))
        val r = Regex.escape(releasePrefix.trim().trimEnd('/'))
        val f = Regex.escape(featurePrefix.trim().trimEnd('/'))
        return """Merge (?:remote-tracking )?branch '(?:[^/]+/)?($h|$r|$f)/([^']+)'"""
    }

    fun buildActiveRegex(hotfixPrefix: String, releasePrefix: String, featurePrefix: String): Regex {
        val h = Regex.escape(hotfixPrefix.trim().trimEnd('/'))
        val r = Regex.escape(releasePrefix.trim().trimEnd('/'))
        val f = Regex.escape(featurePrefix.trim().trimEnd('/'))
        return Regex("""^(?:[a-zA-Z0-9_\-]+/)?($h|$r|$f)/(.+)$""")
    }

    fun readGitConfig(project: Project, repo: GitRepository, key: String): String? {
        val handler = GitLineHandler(project, repo.root, GitCommand.CONFIG)
        handler.addParameters("--get", key)
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.outputAsJoinedString.trim().ifEmpty { null } else null
    }

    fun writeGitConfig(project: Project, repo: GitRepository, key: String, value: String) {
        val handler = GitLineHandler(project, repo.root, GitCommand.CONFIG)
        handler.addParameters(key, value)
        Git.getInstance().runCommand(handler)
    }

    fun getEffectiveProductionBranch(repo: GitRepository, configured: String): String {
        val trimmed = configured.trim()
        if (trimmed.isNotEmpty() && repo.branches.findLocalBranch(trimmed) != null) {
            return trimmed
        }
        if (repo.branches.findLocalBranch("main") != null) return "main"
        if (repo.branches.findLocalBranch("master") != null) return "master"
        return if (trimmed.isNotEmpty()) trimmed else "main"
    }

    fun getEffectiveDevelopBranch(repo: GitRepository, configured: String, prodBranch: String): String {
        val trimmed = configured.trim()
        if (trimmed.isNotEmpty() && repo.branches.findLocalBranch(trimmed) != null) {
            return trimmed
        }
        if (repo.branches.findLocalBranch("develop") != null) return "develop"
        if (repo.branches.findLocalBranch("dev") != null) return "dev"
        return prodBranch
    }

    fun isWorkingTreeClean(project: Project, repo: GitRepository): Boolean {
        val handler = GitLineHandler(project, repo.root, GitCommand.STATUS)
        handler.addParameters("--porcelain")
        val result = Git.getInstance().runCommand(handler)
        return result.success() && result.output.none { it.isNotBlank() }
    }

    fun isGitFlowInitialized(project: Project): Boolean {
        val repo = getPrimaryRepo(project) ?: return false
        val settings = GitVersionTrackerSettings.getInstance(project)

        val gitflowMaster = readGitConfig(project, repo, "gitflow.branch.master")
        val gitflowDevelop = readGitConfig(project, repo, "gitflow.branch.develop")
        val hasGitConfig = !gitflowMaster.isNullOrBlank() && !gitflowDevelop.isNullOrBlank()

        if (!hasGitConfig && !settings.state.isInitialized) {
            return false
        }

        val devBranch = gitflowDevelop ?: settings.state.developBranch
        if (devBranch.isBlank() || repo.branches.findLocalBranch(devBranch) == null) {
            return false
        }

        // If configured via .git/config, ensure plugin settings match
        if (hasGitConfig && !settings.state.isInitialized) {
            settings.state.isInitialized = true
            settings.state.productionBranch = gitflowMaster!!
            settings.state.developBranch = gitflowDevelop!!
            readGitConfig(project, repo, "gitflow.prefix.feature")?.let { settings.state.featurePrefix = it }
            readGitConfig(project, repo, "gitflow.prefix.release")?.let { settings.state.releasePrefix = it }
            readGitConfig(project, repo, "gitflow.prefix.hotfix")?.let { settings.state.hotfixPrefix = it }
            readGitConfig(project, repo, "gitflow.prefix.versiontag")?.let { settings.state.tagPrefix = it }
        }

        return true
    }

    fun checkGitFlowInitialized(project: Project, onInitialized: () -> Unit): Boolean {
        if (isGitFlowInitialized(project)) {
            return true
        }

        val choice = Messages.showYesNoDialog(
            project,
            "GitFlow가 아직 초기화되지 않았습니다.\n" +
            "Feature, Release, Hotfix 브랜치를 생성하거나 관리하려면 먼저 'GitFlow Init'을 통해 기본 브랜치(main/develop) 및 접두사를 설정해야 합니다.\n\n" +
            "지금 GitFlow Init 설정을 진행하시겠습니까?",
            "GitFlow 초기화 필요 (GitFlow Init Required)",
            "GitFlow Init 실행",
            "취소",
            Messages.getWarningIcon()
        )

        if (choice == Messages.YES) {
            promptInitGitFlow(project) {
                if (isGitFlowInitialized(project)) {
                    onInitialized()
                }
            }
        }
        return false
    }

    fun pullAndRefresh(project: Project, onFinished: (() -> Unit)? = null) {
        val repo = getPrimaryRepo(project) ?: run {
            notifyMessage(project, "No Git repository found in project.", NotificationType.ERROR)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Running Git Pull...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Executing git pull..."

                val handler = GitLineHandler(project, repo.root, GitCommand.PULL)
                val result = Git.getInstance().runCommand(handler)
                repo.update()

                if (result.success()) {
                    notifyMessage(project, "Git Pull completed successfully. Versions updated.", NotificationType.INFORMATION)
                    ApplicationManager.getApplication().invokeLater {
                        onFinished?.invoke()
                    }
                } else {
                    notifyMessage(project, "Git Pull failed: ${result.errorOutputAsJoinedString}", NotificationType.ERROR)
                }
            }
        })
    }

    fun checkoutBranch(project: Project, branchName: String, onFinished: (() -> Unit)? = null) {
        val repo = getPrimaryRepo(project) ?: run {
            notifyMessage(project, "No Git repository found in project.", NotificationType.ERROR)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Checking out $branchName...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                if (!isWorkingTreeClean(project, repo)) {
                    notifyMessage(project, "Cannot checkout '$branchName': You have uncommitted changes. Please commit or stash them first.", NotificationType.WARNING)
                    return
                }

                val handler = GitLineHandler(project, repo.root, GitCommand.CHECKOUT)
                handler.addParameters(branchName)
                val result = Git.getInstance().runCommand(handler)
                repo.update()

                if (result.success()) {
                    notifyMessage(project, "Switched to branch '$branchName'.", NotificationType.INFORMATION)
                    onFinished?.let { ApplicationManager.getApplication().invokeLater(it) }
                } else {
                    notifyMessage(project, "Failed to checkout '$branchName': ${result.errorOutputAsJoinedString}", NotificationType.ERROR)
                }
            }
        })
    }

    fun initGitFlow(
        project: Project,
        repo: GitRepository,
        prodBranch: String,
        devBranch: String,
        createDevBranch: Boolean,
        featPrefix: String,
        relPrefix: String,
        hotPrefix: String,
        tagPrefix: String,
        onFinished: (() -> Unit)? = null
    ) {
        if (!isWorkingTreeClean(project, repo)) {
            notifyMessage(project, "Cannot initialize GitFlow: You have uncommitted changes. Please commit or stash them first.", NotificationType.WARNING)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Initializing GitFlow...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                // 1. develop 브랜치 생성 (필요 시)
                if (createDevBranch && repo.branches.findLocalBranch(devBranch) == null) {
                    indicator.text = "Creating branch $devBranch from $prodBranch..."
                    val handler = GitLineHandler(project, repo.root, GitCommand.BRANCH)
                    handler.addParameters(devBranch, prodBranch)
                    val res = Git.getInstance().runCommand(handler)
                    if (!res.success()) {
                        notifyMessage(project, "Failed to create branch '$devBranch': ${res.errorOutputAsJoinedString}", NotificationType.ERROR)
                        return
                    }
                }

                // 2. .git/config 에 표준 GitFlow 설정 저장
                indicator.text = "Writing GitFlow configs to .git/config..."
                writeGitConfig(project, repo, "gitflow.branch.master", prodBranch)
                writeGitConfig(project, repo, "gitflow.branch.develop", devBranch)
                writeGitConfig(project, repo, "gitflow.prefix.feature", featPrefix)
                writeGitConfig(project, repo, "gitflow.prefix.release", relPrefix)
                writeGitConfig(project, repo, "gitflow.prefix.hotfix", hotPrefix)
                writeGitConfig(project, repo, "gitflow.prefix.versiontag", tagPrefix)

                // 3. 플러그인 설정 및 정규식 동기화
                val settings = GitVersionTrackerSettings.getInstance(project)
                settings.state.productionBranch = prodBranch
                settings.state.developBranch = devBranch
                settings.state.featurePrefix = featPrefix
                settings.state.releasePrefix = relPrefix
                settings.state.hotfixPrefix = hotPrefix
                settings.state.tagPrefix = tagPrefix
                settings.state.pattern = buildRegexPattern(hotPrefix, relPrefix, featPrefix)
                settings.state.isInitialized = true

                repo.update()
                notifyMessage(
                    project,
                    "GitFlow initialized successfully: Production='$prodBranch', Development='$devBranch', Prefixes=[$featPrefix, $relPrefix, $hotPrefix].",
                    NotificationType.INFORMATION
                )

                ApplicationManager.getApplication().invokeLater {
                    onFinished?.invoke()
                }
            }
        })
    }

    fun promptInitGitFlow(project: Project, onFinished: (() -> Unit)? = null) {
        val repo = getPrimaryRepo(project) ?: run {
            notifyMessage(project, "No Git repository found in project.", NotificationType.ERROR)
            return
        }

        if (!isWorkingTreeClean(project, repo)) {
            Messages.showWarningDialog(
                project,
                "작업 공간에 아직 커밋되거나 스태시되지 않은 수정된 파일이 있습니다.\n" +
                "안전한 브랜치 생성 및 초기화를 위해 먼저 변경 사항을 커밋하거나 Stash 해주세요.",
                "GitFlow Init 불가 (수정된 파일 존재)"
            )
            notifyMessage(
                project,
                "Cannot initialize GitFlow: You have uncommitted changes. Please commit or stash them first.",
                NotificationType.WARNING
            )
            return
        }

        val settings = GitVersionTrackerSettings.getInstance(project)

        val prodConfig = readGitConfig(project, repo, "gitflow.branch.master") ?: settings.state.productionBranch
        val devConfig = readGitConfig(project, repo, "gitflow.branch.develop") ?: settings.state.developBranch
        val featConfig = readGitConfig(project, repo, "gitflow.prefix.feature") ?: settings.state.featurePrefix
        val relConfig = readGitConfig(project, repo, "gitflow.prefix.release") ?: settings.state.releasePrefix
        val hotConfig = readGitConfig(project, repo, "gitflow.prefix.hotfix") ?: settings.state.hotfixPrefix
        val tagConfig = readGitConfig(project, repo, "gitflow.prefix.versiontag") ?: settings.state.tagPrefix

        val dialog = GitFlowInitDialog(
            project = project,
            repo = repo,
            currentProd = prodConfig,
            currentDev = devConfig,
            currentFeat = featConfig,
            currentRel = relConfig,
            currentHot = hotConfig,
            currentTag = tagConfig
        )

        if (dialog.showAndGet()) {
            initGitFlow(
                project = project,
                repo = repo,
                prodBranch = dialog.getProductionBranch(),
                devBranch = dialog.getDevelopBranch(),
                createDevBranch = dialog.isCreateDevBranch(),
                featPrefix = dialog.getFeaturePrefix(),
                relPrefix = dialog.getReleasePrefix(),
                hotPrefix = dialog.getHotfixPrefix(),
                tagPrefix = dialog.getTagPrefix(),
                onFinished = onFinished
            )
        }
    }

    fun startBranch(
        project: Project,
        branchType: String,
        identifier: String,
        baseBranch: String,
        onFinished: (() -> Unit)? = null
    ) {
        val repo = getPrimaryRepo(project) ?: run {
            notifyMessage(project, "No Git repository found in project.", NotificationType.ERROR)
            return
        }
        if (!isGitFlowInitialized(project)) {
            notifyMessage(project, "Cannot start branch: GitFlow is not initialized. Please run GitFlow Init first.", NotificationType.WARNING)
            return
        }
        val settings = GitVersionTrackerSettings.getInstance(project)

        val prefix = when (branchType.lowercase()) {
            "hotfix" -> settings.state.hotfixPrefix
            "release" -> settings.state.releasePrefix
            "feature" -> settings.state.featurePrefix
            else -> "$branchType/"
        }
        val newBranchName = "$prefix$identifier"

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Starting GitFlow $branchType ($newBranchName)...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                if (!isWorkingTreeClean(project, repo)) {
                    notifyMessage(project, "Cannot start branch '$newBranchName': You have uncommitted changes. Please commit or stash them first.", NotificationType.WARNING)
                    return
                }

                if (repo.branches.findLocalBranch(newBranchName) != null) {
                    notifyMessage(project, "Branch '$newBranchName' already exists locally.", NotificationType.WARNING)
                    return
                }

                val handler = GitLineHandler(project, repo.root, GitCommand.CHECKOUT)
                handler.addParameters("-b", newBranchName, baseBranch)
                val result = Git.getInstance().runCommand(handler)
                repo.update()

                if (result.success()) {
                    notifyMessage(project, "GitFlow Start: Created and switched to '$newBranchName' from '$baseBranch'.", NotificationType.INFORMATION)
                    onFinished?.let { ApplicationManager.getApplication().invokeLater(it) }
                } else {
                    notifyMessage(project, "Failed to create branch '$newBranchName': ${result.errorOutputAsJoinedString}", NotificationType.ERROR)
                }
            }
        })
    }

    fun finishBranch(
        project: Project,
        branchName: String,
        deleteBranch: Boolean,
        createTag: Boolean,
        onFinished: (() -> Unit)? = null
    ) {
        val repo = getPrimaryRepo(project) ?: run {
            notifyMessage(project, "No Git repository found in project.", NotificationType.ERROR)
            return
        }
        if (!isGitFlowInitialized(project)) {
            notifyMessage(project, "Cannot finish branch: GitFlow is not initialized. Please run GitFlow Init first.", NotificationType.WARNING)
            return
        }
        val settings = GitVersionTrackerSettings.getInstance(project)
        val prodBranch = getEffectiveProductionBranch(repo, settings.state.productionBranch)
        val devBranch = getEffectiveDevelopBranch(repo, settings.state.developBranch, prodBranch)

        val cleanHot = settings.state.hotfixPrefix.trim().trimEnd('/')
        val cleanRel = settings.state.releasePrefix.trim().trimEnd('/')
        val cleanFeat = settings.state.featurePrefix.trim().trimEnd('/')

        val branchType = when {
            branchName.startsWith(settings.state.hotfixPrefix) || branchName.startsWith("$cleanHot/") -> "hotfix"
            branchName.startsWith(settings.state.releasePrefix) || branchName.startsWith("$cleanRel/") -> "release"
            branchName.startsWith(settings.state.featurePrefix) || branchName.startsWith("$cleanFeat/") -> "feature"
            else -> "feature"
        }
        val identifier = branchName.substringAfter('/')

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Finishing GitFlow branch '$branchName'...", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                if (!isWorkingTreeClean(project, repo)) {
                    notifyMessage(project, "Cannot finish '$branchName': You have uncommitted changes. Please commit or stash them first.", NotificationType.WARNING)
                    return
                }

                when (branchType) {
                    "feature" -> {
                        // 1. Checkout devBranch
                        indicator.text = "Checking out $devBranch..."
                        if (!runGit(project, repo, GitCommand.CHECKOUT, devBranch)) {
                            notifyMessage(project, "Failed to switch to '$devBranch'.", NotificationType.ERROR)
                            return
                        }

                        // 2. Merge --no-ff feature branch
                        indicator.text = "Merging $branchName into $devBranch..."
                        val mergeMsg = "Merge branch '$branchName' into $devBranch"
                        if (!runGit(project, repo, GitCommand.MERGE, "--no-ff", "-m", mergeMsg, branchName)) {
                            notifyMessage(project, "Merge conflict while merging '$branchName' into '$devBranch'. Please resolve conflicts.", NotificationType.ERROR)
                            repo.update()
                            return
                        }

                        // 3. Delete branch if requested
                        if (deleteBranch) {
                            indicator.text = "Deleting local branch $branchName..."
                            runGit(project, repo, GitCommand.BRANCH, "-d", branchName)
                        }

                        repo.update()
                        notifyMessage(project, "GitFlow Finish: Merged '$branchName' into '$devBranch'${if (deleteBranch) " and deleted branch" else ""}.", NotificationType.INFORMATION)
                        onFinished?.let { ApplicationManager.getApplication().invokeLater(it) }
                    }

                    "release", "hotfix" -> {
                        val isHotfix = branchType == "hotfix"
                        val tagPrefix = settings.state.tagPrefix
                        val tagName = "$tagPrefix$identifier"

                        // 1. Checkout prodBranch
                        indicator.text = "Checking out $prodBranch..."
                        if (!runGit(project, repo, GitCommand.CHECKOUT, prodBranch)) {
                            notifyMessage(project, "Failed to switch to '$prodBranch'.", NotificationType.ERROR)
                            return
                        }

                        // 2. Merge --no-ff branch into prodBranch
                        indicator.text = "Merging $branchName into $prodBranch..."
                        val mergeMsg = "Merge branch '$branchName' into $prodBranch"
                        if (!runGit(project, repo, GitCommand.MERGE, "--no-ff", "-m", mergeMsg, branchName)) {
                            notifyMessage(project, "Merge conflict while merging '$branchName' into '$prodBranch'. Please resolve conflicts.", NotificationType.ERROR)
                            repo.update()
                            return
                        }

                        // 3. Create Tag if requested
                        if (createTag) {
                            indicator.text = "Creating tag $tagName..."
                            val tagAnnotation = if (isHotfix) "Hotfix $tagName" else "Release $tagName"
                            runGit(project, repo, GitCommand.TAG, "-a", tagName, "-m", tagAnnotation)
                        }

                        // 4. Merge into devBranch if exists and distinct
                        if (devBranch != prodBranch && repo.branches.findLocalBranch(devBranch) != null) {
                            indicator.text = "Checking out $devBranch..."
                            if (runGit(project, repo, GitCommand.CHECKOUT, devBranch)) {
                                indicator.text = "Merging $branchName into $devBranch..."
                                val devMergeMsg = "Merge branch '$branchName' into $devBranch"
                                if (!runGit(project, repo, GitCommand.MERGE, "--no-ff", "-m", devMergeMsg, branchName)) {
                                    notifyMessage(project, "Merged into '$prodBranch', but conflict occurred while merging into '$devBranch'. Please resolve conflicts.", NotificationType.WARNING)
                                    repo.update()
                                    return
                                }
                            }
                        }

                        // 5. Delete branch if requested
                        if (deleteBranch) {
                            indicator.text = "Deleting local branch $branchName..."
                            runGit(project, repo, GitCommand.BRANCH, "-d", branchName)
                        }

                        // 6. Return to target branch
                        val returnBranch = if (isHotfix) prodBranch else devBranch
                        runGit(project, repo, GitCommand.CHECKOUT, returnBranch)

                        repo.update()
                        notifyMessage(project, "GitFlow Finish: '$branchName' merged into $prodBranch & $devBranch${if (createTag) ", tagged as '$tagName'" else ""}.", NotificationType.INFORMATION)
                        onFinished?.let { ApplicationManager.getApplication().invokeLater(it) }
                    }

                    else -> {
                        // General branch fallback
                        indicator.text = "Checking out $devBranch..."
                        if (runGit(project, repo, GitCommand.CHECKOUT, devBranch)) {
                            val mergeMsg = "Merge branch '$branchName' into $devBranch"
                            runGit(project, repo, GitCommand.MERGE, "--no-ff", "-m", mergeMsg, branchName)
                            if (deleteBranch) runGit(project, repo, GitCommand.BRANCH, "-d", branchName)
                            repo.update()
                            notifyMessage(project, "Merged '$branchName' into '$devBranch'.", NotificationType.INFORMATION)
                            onFinished?.let { ApplicationManager.getApplication().invokeLater(it) }
                        }
                    }
                }
            }

            private fun runGit(project: Project, repo: GitRepository, command: GitCommand, vararg params: String): Boolean {
                val handler = GitLineHandler(project, repo.root, command)
                handler.addParameters(*params)
                val result = Git.getInstance().runCommand(handler)
                return result.success()
            }
        })
    }

    fun promptStartBranch(project: Project, branchType: String, suggestedIdentifier: String, onFinished: (() -> Unit)? = null) {
        if (!checkGitFlowInitialized(project) {
            promptStartBranch(project, branchType, suggestedIdentifier, onFinished)
        }) {
            return
        }

        val repo = getPrimaryRepo(project) ?: run {
            notifyMessage(project, "No Git repository found in project.", NotificationType.ERROR)
            return
        }
        val settings = GitVersionTrackerSettings.getInstance(project)
        val prodBranch = getEffectiveProductionBranch(repo, settings.state.productionBranch)
        val devBranch = getEffectiveDevelopBranch(repo, settings.state.developBranch, prodBranch)

        val baseBranch = when (branchType.lowercase()) {
            "hotfix" -> prodBranch
            else -> devBranch
        }

        val prefix = when (branchType.lowercase()) {
            "hotfix" -> settings.state.hotfixPrefix
            "release" -> settings.state.releasePrefix
            "feature" -> settings.state.featurePrefix
            else -> "$branchType/"
        }

        val dialog = GitFlowStartDialog(project, branchType, prefix, suggestedIdentifier, baseBranch)
        if (dialog.showAndGet()) {
            val identifier = dialog.getIdentifier()
            startBranch(project, branchType, identifier, baseBranch, onFinished)
        }
    }

    fun promptFinishBranch(project: Project, branchName: String, onFinished: (() -> Unit)? = null) {
        if (!checkGitFlowInitialized(project) {
            promptFinishBranch(project, branchName, onFinished)
        }) {
            return
        }

        val repo = getPrimaryRepo(project) ?: run {
            notifyMessage(project, "No Git repository found in project.", NotificationType.ERROR)
            return
        }
        val settings = GitVersionTrackerSettings.getInstance(project)
        val prodBranch = getEffectiveProductionBranch(repo, settings.state.productionBranch)
        val devBranch = getEffectiveDevelopBranch(repo, settings.state.developBranch, prodBranch)

        val dialog = GitFlowFinishDialog(project, branchName, prodBranch, devBranch, settings.state.tagPrefix)
        if (dialog.showAndGet()) {
            finishBranch(
                project = project,
                branchName = branchName,
                deleteBranch = dialog.isDeleteBranch(),
                createTag = dialog.isCreateTag(),
                onFinished = onFinished
            )
        }
    }
}

/**
 * Dialog for initializing GitFlow branches and directory prefixes.
 */
class GitFlowInitDialog(
    project: Project,
    repo: GitRepository,
    currentProd: String,
    currentDev: String,
    currentFeat: String,
    currentRel: String,
    currentHot: String,
    currentTag: String
) : DialogWrapper(project, true) {

    private val localBranchNames = repo.branches.localBranches.map { it.name }

    private val prodCombo = ComboBox(
        if (localBranchNames.isNotEmpty()) localBranchNames.toTypedArray() else arrayOf("main", "master")
    ).apply {
        isEditable = true
        selectedItem = if (localBranchNames.contains(currentProd)) currentProd
            else localBranchNames.firstOrNull { it == "main" || it == "master" } ?: "main"
    }

    private val devCombo = ComboBox(
        if (localBranchNames.isNotEmpty()) localBranchNames.toTypedArray() else arrayOf("develop")
    ).apply {
        isEditable = true
        selectedItem = if (localBranchNames.contains(currentDev)) currentDev else "develop"
    }

    private val createDevBranchCheckbox = JBCheckBox("Create develop branch from production branch if missing", true)

    private val featField = JBTextField(currentFeat, 20)
    private val relField = JBTextField(currentRel, 20)
    private val hotField = JBTextField(currentHot, 20)
    private val tagField = JBTextField(currentTag, 10)

    init {
        title = "Initialize GitFlow (GitFlow Init)"
        updateCreateCheckbox()
        devCombo.addActionListener { updateCreateCheckbox() }
        prodCombo.addActionListener { updateCreateCheckbox() }
        init()
    }

    private fun updateCreateCheckbox() {
        val selectedDev = getDevelopBranch()
        val exists = localBranchNames.contains(selectedDev)
        createDevBranchCheckbox.isEnabled = !exists
        createDevBranchCheckbox.isSelected = !exists
        createDevBranchCheckbox.text = "Create '$selectedDev' branch from '${getProductionBranch()}' if missing"
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 14))

        val headerLabel = JBLabel("<html><b>Configure GitFlow branch names and directory prefixes.</b><br/>" +
                "<small>This establishes branching conventions across your team and local Git repository.</small></html>")

        val formPanel = JPanel(GridLayout(6, 2, 8, 8))
        formPanel.add(JBLabel("Production Branch (Releases):"))
        formPanel.add(prodCombo)

        formPanel.add(JBLabel("Next Release Branch (Development):"))
        formPanel.add(devCombo)

        formPanel.add(JBLabel("Feature Branches Prefix:"))
        formPanel.add(featField)

        formPanel.add(JBLabel("Release Branches Prefix:"))
        formPanel.add(relField)

        formPanel.add(JBLabel("Hotfix Branches Prefix:"))
        formPanel.add(hotField)

        formPanel.add(JBLabel("Version Tag Prefix:"))
        formPanel.add(tagField)

        panel.add(headerLabel, BorderLayout.NORTH)
        panel.add(formPanel, BorderLayout.CENTER)
        panel.add(createDevBranchCheckbox, BorderLayout.SOUTH)
        return panel
    }

    fun getProductionBranch(): String = (prodCombo.editor.item as? String ?: prodCombo.selectedItem as? String ?: "main").trim().ifEmpty { "main" }
    fun getDevelopBranch(): String = (devCombo.editor.item as? String ?: devCombo.selectedItem as? String ?: "develop").trim().ifEmpty { "develop" }
    fun isCreateDevBranch(): Boolean = createDevBranchCheckbox.isEnabled && createDevBranchCheckbox.isSelected
    fun getFeaturePrefix(): String = featField.text.trim().let { if (it.endsWith("/")) it else "$it/" }
    fun getReleasePrefix(): String = relField.text.trim().let { if (it.endsWith("/")) it else "$it/" }
    fun getHotfixPrefix(): String = hotField.text.trim().let { if (it.endsWith("/")) it else "$it/" }
    fun getTagPrefix(): String = tagField.text.trim()

    override fun doValidate(): ValidationInfo? {
        val prod = getProductionBranch()
        val dev = getDevelopBranch()
        if (prod == dev) {
            return ValidationInfo("Production and development branches must be distinct", devCombo)
        }
        if (getFeaturePrefix().isBlank()) return ValidationInfo("Feature prefix cannot be empty", featField)
        if (getReleasePrefix().isBlank()) return ValidationInfo("Release prefix cannot be empty", relField)
        if (getHotfixPrefix().isBlank()) return ValidationInfo("Hotfix prefix cannot be empty", hotField)
        return null
    }
}

/**
 * Dialog for starting a new GitFlow branch.
 */
class GitFlowStartDialog(
    project: Project,
    private val branchType: String,
    private val branchPrefix: String,
    suggestedIdentifier: String,
    baseBranch: String
) : DialogWrapper(project, true) {

    private val identifierField = JBTextField(suggestedIdentifier, 30)
    private val baseBranchLabel = JBLabel("<html><b>$baseBranch</b></html>")
    private val previewLabel = JBLabel()

    init {
        title = "Start GitFlow ${branchType.replaceFirstChar { it.uppercase() }}"
        init()
        updatePreview()
        identifierField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                updatePreview()
            }
        })
    }

    private fun updatePreview() {
        val input = identifierField.text.trim()
        val display = if (input.isEmpty()) "..." else input
        previewLabel.text = "<html>Branch to create: <code><b>$branchPrefix$display</b></code></html>"
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))

        val formPanel = JPanel(GridLayout(3, 2, 8, 8))
        formPanel.add(JBLabel("Base Branch:"))
        formPanel.add(baseBranchLabel)

        val inputLabel = when (branchType.lowercase()) {
            "feature" -> "Feature Name / ID:"
            "release" -> "Release Version:"
            "hotfix" -> "Hotfix Version:"
            else -> "Identifier:"
        }
        formPanel.add(JBLabel(inputLabel))
        formPanel.add(identifierField)

        formPanel.add(JBLabel("Preview:"))
        formPanel.add(previewLabel)

        panel.add(formPanel, BorderLayout.CENTER)
        return panel
    }

    fun getIdentifier(): String = identifierField.text.trim()

    override fun doValidate(): ValidationInfo? {
        val text = getIdentifier()
        if (text.isEmpty()) {
            return ValidationInfo("Identifier cannot be empty", identifierField)
        }
        if (text.contains(" ") || text.contains("..") || text.contains("~") || text.contains("^") || text.contains(":")) {
            return ValidationInfo("Branch name cannot contain spaces or special Git characters", identifierField)
        }
        return null
    }
}

/**
 * Dialog for finishing a GitFlow branch.
 */
class GitFlowFinishDialog(
    project: Project,
    private val branchName: String,
    private val prodBranch: String,
    private val devBranch: String,
    tagPrefix: String
) : DialogWrapper(project, true) {

    private val parts = branchName.split('/', limit = 2)
    private val branchType = if (parts.size == 2) parts[0].lowercase() else "feature"
    private val identifier = if (parts.size == 2) parts[1] else branchName

    private val tagName = "$tagPrefix$identifier"
    private val tagCheckbox = JBCheckBox("Create Git tag '$tagName'", branchType != "feature")
    private val deleteCheckbox = JBCheckBox("Delete local branch '$branchName' after merge", true)

    init {
        title = "Finish GitFlow Branch"
        if (branchType == "feature") {
            tagCheckbox.isEnabled = false
            tagCheckbox.isSelected = false
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12))

        val descHtml = when (branchType) {
            "feature" -> "<html>Finish feature branch <code><b>$branchName</b></code>:<br/>" +
                    "• Checkout and merge into <b>$devBranch</b> (<code>--no-ff</code>)<br/>" +
                    "• Commit message: <code>Merge branch '$branchName' into $devBranch</code><br/>" +
                    "• Clean up local feature branch</html>"
            "release" -> "<html>Finish release branch <code><b>$branchName</b></code>:<br/>" +
                    "• Checkout and merge into <b>$prodBranch</b> (<code>--no-ff</code>)<br/>" +
                    "• Prod commit: <code>Merge branch '$branchName' into $prodBranch</code><br/>" +
                    "• Tag release as <b>$tagName</b> (Message: <code>Release $tagName</code>)<br/>" +
                    "• Merge back into <b>$devBranch</b> (<code>--no-ff</code>)<br/>" +
                    "• Clean up local release branch</html>"
            "hotfix" -> "<html>Finish hotfix branch <code><b>$branchName</b></code>:<br/>" +
                    "• Checkout and merge into <b>$prodBranch</b> (<code>--no-ff</code>)<br/>" +
                    "• Prod commit: <code>Merge branch '$branchName' into $prodBranch</code><br/>" +
                    "• Tag hotfix as <b>$tagName</b> (Message: <code>Hotfix $tagName</code>)<br/>" +
                    "• Merge back into <b>$devBranch</b> (<code>--no-ff</code>)<br/>" +
                    "• Clean up local hotfix branch</html>"
            else -> "<html>Merge branch <code><b>$branchName</b></code> into <b>$devBranch</b> (<code>--no-ff</code>)</html>"
        }

        val descLabel = JBLabel(descHtml)
        val optionsPanel = JPanel(GridLayout(2, 1, 0, 6))
        optionsPanel.add(tagCheckbox)
        optionsPanel.add(deleteCheckbox)

        panel.add(descLabel, BorderLayout.NORTH)
        panel.add(optionsPanel, BorderLayout.CENTER)
        return panel
    }

    fun isCreateTag(): Boolean = tagCheckbox.isSelected
    fun isDeleteBranch(): Boolean = deleteCheckbox.isSelected
}

/**
 * Dialog for configuring regex patterns and GitFlow base branches.
 */
class GitVersionSettingsDialog(
    project: Project,
    currentPattern: String,
    currentProd: String,
    currentDev: String,
    currentFeat: String,
    currentRel: String,
    currentHot: String,
    currentTagPrefix: String
) : DialogWrapper(project, true) {

    private val patternField = JBTextField(currentPattern, 40)
    private val resetButton = JButton("Reset Pattern")
    private val prodBranchField = JBTextField(currentProd, 20)
    private val devBranchField = JBTextField(currentDev, 20)
    private val featPrefixField = JBTextField(currentFeat, 20)
    private val relPrefixField = JBTextField(currentRel, 20)
    private val hotPrefixField = JBTextField(currentHot, 20)
    private val tagPrefixField = JBTextField(currentTagPrefix, 10)

    init {
        title = "Configure Git Branch Version Tracker & GitFlow"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 12))

        // Regex Panel
        val regexPanel = JPanel(BorderLayout(8, 6))
        val regexTop = JPanel(BorderLayout(6, 0))
        regexTop.add(JBLabel("Merge Regex Pattern:"), BorderLayout.WEST)
        regexTop.add(patternField, BorderLayout.CENTER)
        regexTop.add(resetButton, BorderLayout.EAST)
        regexPanel.add(regexTop, BorderLayout.NORTH)

        val guideLabel = JBLabel(
            "<html><small>" +
            "<b>Default:</b> <code>" + GitVersionTrackerSettings.DEFAULT_PATTERN + "</code><br/>" +
            "Group 1: Branch Type (hotfix/release/feature) | Group 2: Version identifier" +
            "</small></html>"
        )
        regexPanel.add(guideLabel, BorderLayout.CENTER)

        resetButton.addActionListener {
            patternField.text = GitFlowHelper.buildRegexPattern(hotPrefixField.text, relPrefixField.text, featPrefixField.text)
        }

        // GitFlow Branches Panel
        val gitFlowPanel = JPanel(GridLayout(6, 2, 8, 8))
        gitFlowPanel.add(JBLabel("Production Branch (Main):"))
        gitFlowPanel.add(prodBranchField)
        gitFlowPanel.add(JBLabel("Development Branch (Develop):"))
        gitFlowPanel.add(devBranchField)
        gitFlowPanel.add(JBLabel("Feature Prefix (Directory):"))
        gitFlowPanel.add(featPrefixField)
        gitFlowPanel.add(JBLabel("Release Prefix (Directory):"))
        gitFlowPanel.add(relPrefixField)
        gitFlowPanel.add(JBLabel("Hotfix Prefix (Directory):"))
        gitFlowPanel.add(hotPrefixField)
        gitFlowPanel.add(JBLabel("Release/Hotfix Tag Prefix:"))
        gitFlowPanel.add(tagPrefixField)

        mainPanel.add(regexPanel, BorderLayout.NORTH)
        mainPanel.add(gitFlowPanel, BorderLayout.CENTER)
        return mainPanel
    }

    fun getPattern(): String = patternField.text.trim()
    fun getProductionBranch(): String = prodBranchField.text.trim().ifEmpty { "main" }
    fun getDevelopBranch(): String = devBranchField.text.trim().ifEmpty { "develop" }
    fun getFeaturePrefix(): String = featPrefixField.text.trim().let { if (it.endsWith("/")) it else "$it/" }
    fun getReleasePrefix(): String = relPrefixField.text.trim().let { if (it.endsWith("/")) it else "$it/" }
    fun getHotfixPrefix(): String = hotPrefixField.text.trim().let { if (it.endsWith("/")) it else "$it/" }
    fun getTagPrefix(): String = tagPrefixField.text.trim()

    override fun doValidate(): ValidationInfo? {
        val text = getPattern()
        if (text.isEmpty()) {
            return ValidationInfo("Pattern cannot be empty", patternField)
        }
        return try {
            Regex(text)
            null
        } catch (e: Exception) {
            ValidationInfo("Invalid Regular Expression: ${e.message}", patternField)
        }
    }
}

/**
 * Status bar widget factory registering the bottom-right status bar widget.
 */
class GitVersionTrackerWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = GitVersionStatusBarWidget.WIDGET_ID
    override fun getDisplayName(): String = "Git Branch Version Tracker"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = GitVersionStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

/**
 * Status bar widget positioned at the bottom right.
 * Displays "gitflow version" and refreshes upon git pull (or manual refresh).
 */
class GitVersionStatusBarWidget(private val project: Project) : CustomStatusBarWidget {

    companion object {
        const val WIDGET_ID = "GitVersionTrackerWidget"
    }

    private var myStatusBar: StatusBar? = null
    private val label = JBLabel("FlowTags", FlowTagsIcons.FlowTags, SwingConstants.LEFT).apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "FlowTags - Click to view & manage GitFlow versions"
    }

    private var currentBranchName: String = "unknown"
    private var activeItems: List<GitVersionItem> = emptyList()
    private var mergedItems: List<GitVersionItem> = emptyList()
    private var lastFetchHeadTimestamp: Long = -1L

    init {
        label.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                showPopup()
            }
        })
    }

    override fun ID(): String = WIDGET_ID

    override fun install(statusBar: StatusBar) {
        myStatusBar = statusBar

        // 초기 fetch_head 타임스탬프 기록
        lastFetchHeadTimestamp = GitFlowHelper.getFetchHeadTimestamp(project)

        // Git 이벤트 리스너: git pull (FETCH_HEAD 변경) 발생 시에만 전체 로그 갱신!
        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repo ->
            val currentFetchTime = GitFlowHelper.getFetchHeadTimestamp(project)
            val isPull = (lastFetchHeadTimestamp != -1L && currentFetchTime > lastFetchHeadTimestamp)
            lastFetchHeadTimestamp = currentFetchTime

            if (isPull) {
                loadGitVersions()
            } else {
                val branch = repo.currentBranchName ?: "unknown"
                if (branch != currentBranchName) {
                    ApplicationManager.getApplication().invokeLater {
                        currentBranchName = branch
                    }
                }
            }
        })

        loadGitVersions()
    }

    override fun getComponent(): JComponent = label

    fun loadGitVersions() {
        val repoManager = GitRepositoryManager.getInstance(project)
        val repositories = repoManager.repositories
        if (repositories.isEmpty()) {
            activeItems = emptyList()
            mergedItems = emptyList()
            currentBranchName = "no git"
            return
        }

        val settings = GitVersionTrackerSettings.getInstance(project)
        val patternString = settings.state.pattern
        val regex = try {
            Regex(patternString)
        } catch (_: Exception) {
            Regex(GitVersionTrackerSettings.DEFAULT_PATTERN)
        }

        val activeRegex = GitFlowHelper.buildActiveRegex(
            settings.state.hotfixPrefix,
            settings.state.releasePrefix,
            settings.state.featurePrefix
        )

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Loading Git Branch Versions...", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val collectedActive = mutableListOf<GitVersionItem>()
                val collectedMerged = mutableListOf<GitVersionItem>()
                val seenActiveBranches = mutableSetOf<String>()
                var detectedCurrentBranch = "unknown"

                for (repo in repositories) {
                    val branchName = repo.currentBranchName
                    if (branchName != null) {
                        detectedCurrentBranch = branchName
                    }

                    // 1. 진행 중인(Active) 로컬 브랜치 탐색
                    for (branch in repo.branches.localBranches) {
                        val bName = branch.name
                        if (!seenActiveBranches.add(bName)) continue

                        val match = activeRegex.find(bName)
                        if (match != null) {
                            val branchType = match.groupValues[1]
                            val identifier = match.groupValues[2]
                            val isCurr = (bName == detectedCurrentBranch)
                            collectedActive.add(
                                GitVersionItem(
                                    type = branchType,
                                    version = identifier,
                                    date = if (isCurr) "Active (Current)" else "Active",
                                    commitHash = if (isCurr) (repo.currentRevision?.take(7) ?: "-") else "-",
                                    rawSubject = if (isCurr) "Current branch ($bName) 🌿" else "Active branch ($bName)",
                                    isFinished = false,
                                    isCurrent = isCurr,
                                    fullBranchName = bName
                                )
                            )
                        }
                    }

                    // 2. 종료된(Merged) 커밋 로그 탐색
                    val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
                    handler.addParameters(
                        "-n", "500",
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

                                val matchResult = regex.find(subject)
                                if (matchResult != null) {
                                    val branchType = if (matchResult.groupValues.size > 2) matchResult.groupValues[1] else "custom"
                                    val versionString = if (matchResult.groupValues.size > 2) matchResult.groupValues[2] else matchResult.groupValues[1]

                                    collectedMerged.add(
                                        GitVersionItem(
                                            type = branchType,
                                            version = versionString,
                                            date = commitDate,
                                            commitHash = commitHash,
                                            rawSubject = subject,
                                            isFinished = true,
                                            isCurrent = false,
                                            fullBranchName = "$branchType/$versionString"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                ApplicationManager.getApplication().invokeLater {
                    currentBranchName = detectedCurrentBranch
                    activeItems = collectedActive
                    mergedItems = collectedMerged
                }
            }
        })
    }

    private fun createCopyAction(title: String, textToCopy: String, icon: Icon? = null): AnAction {
        return object : AnAction(title, "Copy '$textToCopy' to clipboard (클릭 시 복사)", icon) {
            override fun actionPerformed(e: AnActionEvent) {
                CopyPasteManager.getInstance().setContents(StringSelection(textToCopy))
                notifyMessage("Copied '$textToCopy' to clipboard. (클립보드에 복사되었습니다)", NotificationType.INFORMATION)
            }
        }
    }

    private fun createDisabledAction(title: String): AnAction {
        return object : AnAction(title) {
            override fun actionPerformed(e: AnActionEvent) {}
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = false
            }
        }
    }

    private fun showPopup() {
        val rootGroup = DefaultActionGroup()

        // 1. 현재 브랜치 정보 (클릭 시 복사)
        rootGroup.addSeparator("Current Branch")
        rootGroup.add(createCopyAction("Current: $currentBranchName", currentBranchName, AllIcons.Vcs.Branch))

        // 2. 다음 버전 추천: 커밋 내역(merged)과 작업 중인 브랜치(active) 모두 확인하여 가장 최신/높은 버전 기준 계산
        val latestVersionForBump = SemVerHelper.findHighestVersion(activeItems, mergedItems)
        val suggestion = SemVerHelper.suggestNext(latestVersionForBump)

        rootGroup.addSeparator("Suggested Next Versions (Click to Copy)")
        rootGroup.add(createCopyAction("Next Hotfix:   ${suggestion.nextHotfix} (Patch)", suggestion.nextHotfix, FlowTagsIcons.Hotfix))
        rootGroup.add(createCopyAction("Next Release:  ${suggestion.nextMinorRelease} (Minor)", suggestion.nextMinorRelease, FlowTagsIcons.Release))

        // 3. 진행 중인(Active) 브랜치 목록 (클릭 시 버전 복사)
        rootGroup.addSeparator("Active Branches (Click to Copy)")
        if (activeItems.isEmpty()) {
            rootGroup.add(createDisabledAction("No active hotfix/release/feature branches"))
        } else {
            for (item in activeItems) {
                val suffix = if (item.isCurrent) " (Current)" else ""
                val title = "[${item.type}]  ${item.version}$suffix"
                val icon = when (item.type.lowercase()) {
                    "hotfix" -> FlowTagsIcons.Hotfix
                    "release" -> FlowTagsIcons.Release
                    "feature" -> FlowTagsIcons.Feature
                    else -> AllIcons.Vcs.Branch
                }
                rootGroup.add(createCopyAction(title, item.version, icon))
            }
        }

        // 4. 각 브랜치 타입별 상세 이력 서브메뉴 (1-depth 추가)
        val activeHotfixes = activeItems.filter { it.type.equals("hotfix", ignoreCase = true) }
        val mergedHotfixes = mergedItems.filter { it.type.equals("hotfix", ignoreCase = true) }
        val hotfixGroup = DefaultActionGroup(
            "Hotfix History (Active: ${activeHotfixes.size}, Merged: ${mergedHotfixes.size})",
            true
        ).apply {
            templatePresentation.icon = FlowTagsIcons.Hotfix
        }
        if (activeHotfixes.isNotEmpty()) {
            hotfixGroup.addSeparator("Active Branches")
            for (item in activeHotfixes) {
                val suffix = if (item.isCurrent) " (Current)" else ""
                hotfixGroup.add(createCopyAction("${item.version}$suffix", item.version, AllIcons.Vcs.Branch))
            }
        }
        if (mergedHotfixes.isNotEmpty()) {
            hotfixGroup.addSeparator("Merged / Tagged Versions")
            for (item in mergedHotfixes.take(25)) {
                hotfixGroup.add(createCopyAction("${item.version}  (${item.date}, ${item.commitHash})", item.version, AllIcons.Nodes.Tag))
            }
        }
        if (activeHotfixes.isEmpty() && mergedHotfixes.isEmpty()) {
            hotfixGroup.add(createDisabledAction("No hotfix branches or history"))
        }

        // Release 서브메뉴
        val activeReleases = activeItems.filter { it.type.equals("release", ignoreCase = true) }
        val mergedReleases = mergedItems.filter { it.type.equals("release", ignoreCase = true) }
        val releaseGroup = DefaultActionGroup(
            "Release History (Active: ${activeReleases.size}, Merged: ${mergedReleases.size})",
            true
        ).apply {
            templatePresentation.icon = FlowTagsIcons.Release
        }
        if (activeReleases.isNotEmpty()) {
            releaseGroup.addSeparator("Active Branches")
            for (item in activeReleases) {
                val suffix = if (item.isCurrent) " (Current)" else ""
                releaseGroup.add(createCopyAction("${item.version}$suffix", item.version, AllIcons.Vcs.Branch))
            }
        }
        if (mergedReleases.isNotEmpty()) {
            releaseGroup.addSeparator("Merged / Tagged Versions")
            for (item in mergedReleases.take(25)) {
                releaseGroup.add(createCopyAction("${item.version}  (${item.date}, ${item.commitHash})", item.version, AllIcons.Nodes.Tag))
            }
        }
        if (activeReleases.isEmpty() && mergedReleases.isEmpty()) {
            releaseGroup.add(createDisabledAction("No release branches or history"))
        }

        // Feature 서브메뉴
        val activeFeatures = activeItems.filter { it.type.equals("feature", ignoreCase = true) }
        val mergedFeatures = mergedItems.filter { it.type.equals("feature", ignoreCase = true) }
        val featureGroup = DefaultActionGroup(
            "Feature History (Active: ${activeFeatures.size}, Merged: ${mergedFeatures.size})",
            true
        ).apply {
            templatePresentation.icon = FlowTagsIcons.Feature
        }
        if (activeFeatures.isNotEmpty()) {
            featureGroup.addSeparator("Active Branches")
            for (item in activeFeatures) {
                val suffix = if (item.isCurrent) " (Current)" else ""
                featureGroup.add(createCopyAction("${item.version}$suffix", item.version, AllIcons.Vcs.Branch))
            }
        }
        if (mergedFeatures.isNotEmpty()) {
            featureGroup.addSeparator("Merged Versions")
            for (item in mergedFeatures.take(25)) {
                featureGroup.add(createCopyAction("${item.version}  (${item.date}, ${item.commitHash})", item.version, AllIcons.Nodes.Tag))
            }
        }
        if (activeFeatures.isEmpty() && mergedFeatures.isEmpty()) {
            featureGroup.add(createDisabledAction("No feature branches or history"))
        }

        // 기타 타입
        val otherMerged = mergedItems.filter {
            !it.type.equals("hotfix", true) && !it.type.equals("release", true) && !it.type.equals("feature", true)
        }
        val otherGroup = if (otherMerged.isNotEmpty()) {
            DefaultActionGroup("Other Types History", true).apply {
                templatePresentation.icon = AllIcons.Vcs.Branch
                for (item in otherMerged.take(25)) {
                    add(createCopyAction("[${item.type}] ${item.version}  (${item.date})", item.version, AllIcons.Nodes.Tag))
                }
            }
        } else null

        // 최상위 메뉴는 딱 한 칸(1-depth)으로 히스토리 그룹 구성
        rootGroup.addSeparator("History")
        val historyRootGroup = DefaultActionGroup("Version History (내역 보기)", true).apply {
            templatePresentation.icon = AllIcons.Vcs.History
        }

        // 최신 종료 버전 요약 (히스토리 내부 1-depth 안에 포함)
        val latestMergedHotfix = mergedItems.firstOrNull { it.type.equals("hotfix", ignoreCase = true) }
        val latestMergedRelease = mergedItems.firstOrNull { it.type.equals("release", ignoreCase = true) }
        val latestMergedFeature = mergedItems.firstOrNull { it.type.equals("feature", ignoreCase = true) }
        if (latestMergedHotfix != null || latestMergedRelease != null || latestMergedFeature != null) {
            historyRootGroup.addSeparator("Latest Finished Summary")
            if (latestMergedHotfix != null) {
                historyRootGroup.add(createCopyAction("[hotfix]   ${latestMergedHotfix.version}  (${latestMergedHotfix.date})", latestMergedHotfix.version, FlowTagsIcons.Hotfix))
            }
            if (latestMergedRelease != null) {
                historyRootGroup.add(createCopyAction("[release]  ${latestMergedRelease.version}  (${latestMergedRelease.date})", latestMergedRelease.version, FlowTagsIcons.Release))
            }
            if (latestMergedFeature != null) {
                historyRootGroup.add(createCopyAction("[feature]  ${latestMergedFeature.version}  (${latestMergedFeature.date})", latestMergedFeature.version, FlowTagsIcons.Feature))
            }
        }

        historyRootGroup.addSeparator("Branch Type History")
        historyRootGroup.add(hotfixGroup)
        historyRootGroup.add(releaseGroup)
        historyRootGroup.add(featureGroup)
        if (otherGroup != null) {
            historyRootGroup.add(otherGroup)
        }

        rootGroup.add(historyRootGroup)

        // 6. 신규 생성 및 브랜치 작업 (1-depth 서브메뉴로 완전 분리/격리)
        rootGroup.addSeparator("GitFlow Management")
        val gitFlowGroup = DefaultActionGroup("GitFlow Operations", true).apply {
            templatePresentation.icon = AllIcons.Vcs.Merge
        }

        val isInitialized = GitFlowHelper.isGitFlowInitialized(project)
        if (!isInitialized) {
            gitFlowGroup.add(object : AnAction("GitFlow Not Initialized (Run Init...)", "Configure base branches and directory prefixes before creating branches", AllIcons.General.Warning) {
                override fun actionPerformed(e: AnActionEvent) {
                    GitFlowHelper.promptInitGitFlow(project) {
                        loadGitVersions()
                    }
                }
            })
            gitFlowGroup.addSeparator()
        } else {
            gitFlowGroup.add(object : AnAction("GitFlow Settings (Re-Init)...", "Configure base branches and directory prefixes", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    GitFlowHelper.promptInitGitFlow(project) {
                        loadGitVersions()
                    }
                }
            })
        }
        gitFlowGroup.addSeparator("Start New Branch")

        gitFlowGroup.add(object : AnAction("Start Hotfix... (${suggestion.nextHotfix})", "Start hotfix from main/master", FlowTagsIcons.Hotfix) {
            override fun actionPerformed(e: AnActionEvent) {
                GitFlowHelper.promptStartBranch(project, "hotfix", suggestion.nextHotfix) {
                    loadGitVersions()
                }
            }
        })
        gitFlowGroup.add(object : AnAction("Start Release... (${suggestion.nextMinorRelease})", "Start release from develop", FlowTagsIcons.Release) {
            override fun actionPerformed(e: AnActionEvent) {
                GitFlowHelper.promptStartBranch(project, "release", suggestion.nextMinorRelease) {
                    loadGitVersions()
                }
            }
        })
        gitFlowGroup.add(object : AnAction("Start Feature...", "Start feature from develop", FlowTagsIcons.Feature) {
            override fun actionPerformed(e: AnActionEvent) {
                GitFlowHelper.promptStartBranch(project, "feature", "") {
                    loadGitVersions()
                }
            }
        })

        val isCurrentGitFlowBranch = currentBranchName.startsWith("hotfix/") ||
                currentBranchName.startsWith("release/") ||
                currentBranchName.startsWith("feature/")

        if (isCurrentGitFlowBranch) {
            gitFlowGroup.addSeparator("Finish Current Branch")
            gitFlowGroup.add(object : AnAction("Finish Current Branch ('$currentBranchName')...", "Finish and merge current branch", AllIcons.Vcs.Merge) {
                override fun actionPerformed(e: AnActionEvent) {
                    GitFlowHelper.promptFinishBranch(project, currentBranchName) {
                        loadGitVersions()
                    }
                }
            })
        }

        if (activeItems.isNotEmpty()) {
            gitFlowGroup.addSeparator("Manage Active Branches")
            for (item in activeItems) {
                val branchIcon = when (item.type.lowercase()) {
                    "hotfix" -> FlowTagsIcons.Hotfix
                    "release" -> FlowTagsIcons.Release
                    "feature" -> FlowTagsIcons.Feature
                    else -> AllIcons.Vcs.Branch
                }
                val branchOpsGroup = DefaultActionGroup(item.fullBranchName, true).apply {
                    templatePresentation.icon = branchIcon
                }
                if (!item.isCurrent) {
                    branchOpsGroup.add(object : AnAction("Checkout '${item.fullBranchName}'", "Switch to branch", AllIcons.Actions.CheckOut) {
                        override fun actionPerformed(e: AnActionEvent) {
                            GitFlowHelper.checkoutBranch(project, item.fullBranchName) {
                                loadGitVersions()
                            }
                        }
                    })
                }
                branchOpsGroup.add(object : AnAction("Finish '${item.fullBranchName}'...", "Merge and finish branch", AllIcons.Vcs.Merge) {
                    override fun actionPerformed(e: AnActionEvent) {
                        GitFlowHelper.promptFinishBranch(project, item.fullBranchName) {
                            loadGitVersions()
                        }
                    }
                })
                branchOpsGroup.addSeparator()
                branchOpsGroup.add(createCopyAction("Copy Branch Name ('${item.fullBranchName}')", item.fullBranchName, AllIcons.Actions.Copy))
                branchOpsGroup.add(createCopyAction("Copy 'git checkout' Command", "git checkout ${item.fullBranchName}", AllIcons.Actions.Copy))
                gitFlowGroup.add(branchOpsGroup)
            }
        }

        rootGroup.add(gitFlowGroup)

        // 7. 하단 설정 및 새로고침
        rootGroup.addSeparator()

        rootGroup.add(object : AnAction("Git Pull & Refresh", "Run git pull and refresh versions", AllIcons.Vcs.Fetch) {
            override fun actionPerformed(e: AnActionEvent) {
                GitFlowHelper.pullAndRefresh(project) {
                    loadGitVersions()
                }
            }
        })

        rootGroup.add(object : AnAction("Refresh Versions", "Refresh Git branches and logs manually", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                loadGitVersions()
            }
        })

        rootGroup.add(object : AnAction("GitFlow & Regex Settings...", "Configure base branches and regex", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) {
                val settings = GitVersionTrackerSettings.getInstance(project)
                val dialog = GitVersionSettingsDialog(
                    project = project,
                    currentPattern = settings.state.pattern,
                    currentProd = settings.state.productionBranch,
                    currentDev = settings.state.developBranch,
                    currentFeat = settings.state.featurePrefix,
                    currentRel = settings.state.releasePrefix,
                    currentHot = settings.state.hotfixPrefix,
                    currentTagPrefix = settings.state.tagPrefix
                )
                if (dialog.showAndGet()) {
                    settings.state.pattern = dialog.getPattern()
                    settings.state.productionBranch = dialog.getProductionBranch()
                    settings.state.developBranch = dialog.getDevelopBranch()
                    settings.state.featurePrefix = dialog.getFeaturePrefix()
                    settings.state.releasePrefix = dialog.getReleasePrefix()
                    settings.state.hotfixPrefix = dialog.getHotfixPrefix()
                    settings.state.tagPrefix = dialog.getTagPrefix()
                    loadGitVersions()
                    notifyMessage("Settings updated successfully.", NotificationType.INFORMATION)
                }
            }
        })

        rootGroup.add(object : AnAction("Open FlowTags ToolWindow...", "Open bottom FlowTags tool window", FlowTagsIcons.FlowTags) {
            override fun actionPerformed(e: AnActionEvent) {
                val tw = ToolWindowManager.getInstance(project).getToolWindow("FlowTags")
                    ?: ToolWindowManager.getInstance(project).getToolWindow("GitFlow Versions")
                    ?: ToolWindowManager.getInstance(project).getToolWindow("Git Branch Version Tracker")
                tw?.show()
            }
        })

        val dataContext = DataManager.getInstance().getDataContext(label)
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "FlowTags",
            rootGroup,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        )
        popup.setAdText("Tip: Click any version to copy to clipboard (클릭 시 복사)", SwingConstants.LEFT)
        popup.show(RelativePoint(label, Point(0, 0)))
    }


    private fun notifyMessage(content: String, type: NotificationType) {
        GitFlowHelper.notifyMessage(project, content, type)
    }

    override fun dispose() {
        myStatusBar = null
        activeItems = emptyList()
        mergedItems = emptyList()
    }
}

/**
 * Non-editable table model for displaying Git version records.
 */
class GitVersionTableModel : DefaultTableModel(
    arrayOf("Type", "Status", "Version / Branch", "Date", "Commit Hash", "Raw Subject"), 0
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
    private val statusFilterCombo = ComboBox(arrayOf("ALL", "Active (진행중)", "Merged (종료됨)"))
    private val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh)
    private val pullButton = JButton("Pull & Sync", AllIcons.Vcs.Fetch)
    private val copyButton = JButton("Copy", AllIcons.Actions.Copy)
    private val gitFlowButton = JButton("FlowTags ▾", FlowTagsIcons.FlowTags)
    private val checkoutButton = JButton("Checkout", AllIcons.Actions.CheckOut)
    private val settingsButton = JButton("Settings", AllIcons.General.Settings)
    private val statusLabel = JBLabel("Ready")

    private var allItems: List<GitVersionItem> = emptyList()
    private var lastFetchHeadTimestamp: Long = -1L

    private fun styleButton(btn: JButton, tooltip: String) {
        btn.isFocusPainted = false
        btn.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        btn.toolTipText = tooltip
        btn.putClientProperty("JButton.buttonType", "toolBarButton")
    }

    init {
        setupUI()
        registerListeners()

        // 초기 fetch_head 타임스탬프 기록
        lastFetchHeadTimestamp = GitFlowHelper.getFetchHeadTimestamp(project)

        // Git 이벤트 발생 시: git pull (FETCH_HEAD 변경)이 감지될 때만 버전 목록 갱신!
        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repo ->
            val currentFetchTime = GitFlowHelper.getFetchHeadTimestamp(project)
            val isPull = (lastFetchHeadTimestamp != -1L && currentFetchTime > lastFetchHeadTimestamp)
            lastFetchHeadTimestamp = currentFetchTime

            if (isPull) {
                loadGitVersions()
            } else {
                val currentBranch = repo.currentBranchName ?: "HEAD"
                updateStatusLabelWithBranch(currentBranch)
            }
        })

        loadGitVersions()
    }

    private fun setupUI() {
        // Style toolbar buttons
        styleButton(refreshButton, "Refresh versions manually")
        styleButton(pullButton, "Run 'git pull' and sync versions")
        styleButton(copyButton, "Copy selected version to clipboard (선택 버전 복사)")
        styleButton(gitFlowButton, "FlowTags operations: Start branch, Finish branch, Init")
        styleButton(checkoutButton, "Checkout selected active branch")
        styleButton(settingsButton, "Configure GitFlow branches, prefixes, and regex pattern")

        // Top Toolbar
        val topToolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4))
        topToolbar.add(JBLabel("Limit:"))
        topToolbar.add(limitCombo)
        topToolbar.add(JBLabel("Type:"))
        topToolbar.add(typeFilterCombo)
        topToolbar.add(JBLabel("Status:"))
        topToolbar.add(statusFilterCombo)
        topToolbar.add(refreshButton)
        topToolbar.add(pullButton)
        topToolbar.add(copyButton)

        // Visual separator between version view/filters and GitFlow actions
        topToolbar.add(JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(2, 22)
        })

        topToolbar.add(gitFlowButton)
        topToolbar.add(checkoutButton)

        topToolbar.add(JSeparator(SwingConstants.VERTICAL).apply {
            preferredSize = Dimension(2, 22)
        })

        topToolbar.add(settingsButton)

        // Table configurations
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        table.fillsViewportHeight = true
        table.autoCreateRowSorter = true

        table.columnModel.getColumn(0).preferredWidth = 80   // Type
        table.columnModel.getColumn(1).preferredWidth = 90   // Status
        table.columnModel.getColumn(2).preferredWidth = 160  // Version / Branch
        table.columnModel.getColumn(3).preferredWidth = 100  // Date
        table.columnModel.getColumn(4).preferredWidth = 90   // Commit Hash
        table.columnModel.getColumn(5).preferredWidth = 330  // Raw Subject

        // Type column icon renderer
        table.columnModel.getColumn(0).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                val typeStr = value?.toString()?.lowercase() ?: ""
                comp.icon = when (typeStr) {
                    "hotfix" -> FlowTagsIcons.Hotfix
                    "release" -> FlowTagsIcons.Release
                    "feature" -> FlowTagsIcons.Feature
                    else -> null
                }
                return comp
            }
        }

        // Status column icon renderer
        table.columnModel.getColumn(1).cellRenderer = object : DefaultTableCellRenderer() {
            override fun getTableCellRendererComponent(
                table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
            ): Component {
                val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column) as JLabel
                val statusStr = value?.toString() ?: ""
                comp.icon = if (statusStr.startsWith("Active")) AllIcons.Vcs.Branch else AllIcons.Actions.Checked
                return comp
            }
        }

        val scrollPane = JBScrollPane(table)

        // Bottom status panel with copy and checkout guide
        val bottomPanel = JPanel(BorderLayout(8, 2)).apply {
            border = javax.swing.BorderFactory.createEmptyBorder(3, 8, 3, 8)
        }
        bottomPanel.add(statusLabel, BorderLayout.WEST)
        val copyHintLabel = JBLabel("💡 Tip: Double-click row to checkout | Select & click 'Copy' to copy version").apply {
            foreground = JBColor.GRAY
        }
        bottomPanel.add(copyHintLabel, BorderLayout.EAST)

        add(topToolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun registerListeners() {
        refreshButton.addActionListener {
            loadGitVersions()
        }

        pullButton.addActionListener {
            GitFlowHelper.pullAndRefresh(project) {
                loadGitVersions()
            }
        }

        limitCombo.addActionListener {
            loadGitVersions()
        }

        typeFilterCombo.addActionListener {
            applyFilter()
        }

        statusFilterCombo.addActionListener {
            applyFilter()
        }

        copyButton.addActionListener {
            copySelectedVersion()
        }

        checkoutButton.addActionListener {
            val selectedItem = getSelectedItem()
            if (selectedItem != null && !selectedItem.isFinished) {
                GitFlowHelper.checkoutBranch(project, selectedItem.fullBranchName) {
                    loadGitVersions()
                }
            } else {
                notifyMessage("Please select an active (in-progress) branch to checkout.", NotificationType.WARNING)
            }
        }

        gitFlowButton.addActionListener {
            showGitFlowMenu()
        }

        settingsButton.addActionListener {
            val settings = GitVersionTrackerSettings.getInstance(project)
            val dialog = GitVersionSettingsDialog(
                project = project,
                currentPattern = settings.state.pattern,
                currentProd = settings.state.productionBranch,
                currentDev = settings.state.developBranch,
                currentFeat = settings.state.featurePrefix,
                currentRel = settings.state.releasePrefix,
                currentHot = settings.state.hotfixPrefix,
                currentTagPrefix = settings.state.tagPrefix
            )
            if (dialog.showAndGet()) {
                settings.state.pattern = dialog.getPattern()
                settings.state.productionBranch = dialog.getProductionBranch()
                settings.state.developBranch = dialog.getDevelopBranch()
                settings.state.featurePrefix = dialog.getFeaturePrefix()
                settings.state.releasePrefix = dialog.getReleasePrefix()
                settings.state.hotfixPrefix = dialog.getHotfixPrefix()
                settings.state.tagPrefix = dialog.getTagPrefix()
                loadGitVersions()
                notifyMessage("Settings updated successfully.", NotificationType.INFORMATION)
            }
        }

        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val selectedItem = getSelectedItem()
                    if (selectedItem != null && !selectedItem.isFinished && !selectedItem.isCurrent) {
                        val confirm = Messages.showYesNoDialog(
                            project,
                            "Do you want to switch to active branch '${selectedItem.fullBranchName}'?",
                            "Checkout Branch",
                            Messages.getQuestionIcon()
                        )
                        if (confirm == Messages.YES) {
                            GitFlowHelper.checkoutBranch(project, selectedItem.fullBranchName) {
                                loadGitVersions()
                            }
                        }
                    } else {
                        copySelectedVersion()
                    }
                } else if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                    showTableContextMenu(e)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showTableContextMenu(e)
                }
            }
        })
    }

    private fun getSelectedItem(): GitVersionItem? {
        val selectedRow = table.selectedRow
        if (selectedRow == -1) return null
        val modelRow = table.convertRowIndexToModel(selectedRow)
        val version = tableModel.getValueAt(modelRow, 2) as? String ?: return null
        val type = tableModel.getValueAt(modelRow, 0) as? String ?: return null
        val commit = tableModel.getValueAt(modelRow, 4) as? String ?: ""
        return allItems.firstOrNull { it.version == version && it.type.equals(type, ignoreCase = true) && it.commitHash == commit }
            ?: allItems.firstOrNull { it.version == version && it.type.equals(type, ignoreCase = true) }
    }

    private fun createCopyAction(title: String, textToCopy: String, icon: Icon? = null): AnAction {
        return object : AnAction(title, "Copy '$textToCopy' to clipboard", icon) {
            override fun actionPerformed(e: AnActionEvent) {
                CopyPasteManager.getInstance().setContents(StringSelection(textToCopy))
                statusLabel.text = "📋 Copied '$textToCopy' to clipboard! (클립보드에 복사되었습니다)"
                notifyMessage("Copied '$textToCopy' to clipboard. (클립보드에 복사되었습니다)", NotificationType.INFORMATION)
            }
        }
    }

    private fun createDisabledAction(title: String): AnAction {
        return object : AnAction(title) {
            override fun actionPerformed(e: AnActionEvent) {}
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = false
            }
        }
    }

    private fun showTableContextMenu(e: MouseEvent) {
        val row = table.rowAtPoint(e.point)
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row)
        }
        val item = getSelectedItem() ?: return

        val group = DefaultActionGroup()

        if (!item.isFinished) {
            // Active branch context options
            if (!item.isCurrent) {
                group.add(object : AnAction("Checkout '${item.fullBranchName}'", "Switch to branch", AllIcons.Actions.CheckOut) {
                    override fun actionPerformed(ev: AnActionEvent) {
                        GitFlowHelper.checkoutBranch(project, item.fullBranchName) { loadGitVersions() }
                    }
                })
            }

            group.add(object : AnAction("Finish '${item.fullBranchName}'...", "Merge and finish branch", AllIcons.Vcs.Merge) {
                override fun actionPerformed(ev: AnActionEvent) {
                    GitFlowHelper.promptFinishBranch(project, item.fullBranchName) { loadGitVersions() }
                }
            })
            group.addSeparator()

            group.add(createCopyAction("Copy Branch Name ('${item.fullBranchName}')", item.fullBranchName, AllIcons.Actions.Copy))
            group.add(createCopyAction("Copy 'git checkout' Command", "git checkout ${item.fullBranchName}", AllIcons.Actions.Copy))
        }

        group.add(createCopyAction("Copy Version ('${item.version}')", item.version, AllIcons.Actions.Copy))

        if (item.isFinished && item.commitHash.isNotBlank() && item.commitHash != "-") {
            group.add(createCopyAction("Copy Commit Hash ('${item.commitHash}')", item.commitHash, AllIcons.Actions.Copy))
        }

        val dataContext = DataManager.getInstance().getDataContext(table)
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            item.fullBranchName.ifEmpty { item.version },
            group,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        )
        popup.show(RelativePoint(table, e.point))
    }

    private fun showGitFlowMenu() {
        val latestVer = SemVerHelper.findHighestVersionFromItems(allItems)
        val suggestion = SemVerHelper.suggestNext(latestVer)

        val rootGroup = DefaultActionGroup()

        // 1. GitFlow Operations
        rootGroup.addSeparator("GitFlow Operations")
        val isInitialized = GitFlowHelper.isGitFlowInitialized(project)
        if (!isInitialized) {
            rootGroup.add(object : AnAction("GitFlow Not Initialized (Run Init...)", "Configure base branches and directory prefixes before creating branches", AllIcons.General.Warning) {
                override fun actionPerformed(e: AnActionEvent) {
                    GitFlowHelper.promptInitGitFlow(project) { loadGitVersions() }
                }
            })
            rootGroup.addSeparator()
        } else {
            rootGroup.add(object : AnAction("GitFlow Settings (Re-Init)...", "Configure base branches and directory prefixes", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) {
                    GitFlowHelper.promptInitGitFlow(project) { loadGitVersions() }
                }
            })
            rootGroup.addSeparator()
        }

        rootGroup.add(object : AnAction("Start Hotfix... (${suggestion.nextHotfix})", "Start hotfix from main/master", AllIcons.Vcs.Patch) {
            override fun actionPerformed(e: AnActionEvent) {
                GitFlowHelper.promptStartBranch(project, "hotfix", suggestion.nextHotfix) { loadGitVersions() }
            }
        })

        rootGroup.add(object : AnAction("Start Release... (${suggestion.nextMinorRelease})", "Start release from develop", AllIcons.Nodes.Tag) {
            override fun actionPerformed(e: AnActionEvent) {
                GitFlowHelper.promptStartBranch(project, "release", suggestion.nextMinorRelease) { loadGitVersions() }
            }
        })

        rootGroup.add(object : AnAction("Start Feature...", "Start feature from develop", AllIcons.Vcs.Branch) {
            override fun actionPerformed(e: AnActionEvent) {
                GitFlowHelper.promptStartBranch(project, "feature", "") { loadGitVersions() }
            }
        })

        val repo = GitFlowHelper.getPrimaryRepo(project)
        val currentBranch = repo?.currentBranchName ?: ""
        val canFinishCurrent = currentBranch.startsWith("hotfix/") ||
                currentBranch.startsWith("release/") ||
                currentBranch.startsWith("feature/")

        rootGroup.add(object : AnAction("Finish Current Branch ('$currentBranch')...", "Finish and merge current branch", AllIcons.Vcs.Merge) {
            override fun actionPerformed(e: AnActionEvent) {
                GitFlowHelper.promptFinishBranch(project, currentBranch) { loadGitVersions() }
            }
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = canFinishCurrent
            }
        })

        // 2. Next Version Suggestions
        rootGroup.addSeparator("Suggested Next Versions (Click to Copy)")
        rootGroup.add(createCopyAction("Next Hotfix:   ${suggestion.nextHotfix} (Patch)", suggestion.nextHotfix, FlowTagsIcons.Hotfix))
        rootGroup.add(createCopyAction("Next Release:  ${suggestion.nextMinorRelease} (Minor)", suggestion.nextMinorRelease, FlowTagsIcons.Release))

        // 3. Version History Submenus (내역 보기) - 1뎁스 추가하여 맨 처음엔 한 칸으로 구성
        rootGroup.addSeparator("History")
        val historyRootGroup = DefaultActionGroup("Version History (내역 보기)", true).apply {
            templatePresentation.icon = AllIcons.Vcs.History
        }
        val hotfixes = allItems.filter { it.type.equals("hotfix", ignoreCase = true) }
        val releases = allItems.filter { it.type.equals("release", ignoreCase = true) }
        val features = allItems.filter { it.type.equals("feature", ignoreCase = true) }

        val hotfixGroup = DefaultActionGroup("Hotfix History (${hotfixes.size})", true).apply {
            templatePresentation.icon = FlowTagsIcons.Hotfix
        }
        val hotfixActive = hotfixes.filter { !it.isFinished }
        val hotfixMerged = hotfixes.filter { it.isFinished }
        if (hotfixActive.isNotEmpty()) {
            hotfixGroup.addSeparator("Active Branches")
            for (item in hotfixActive) {
                hotfixGroup.add(createCopyAction(item.version, item.version, AllIcons.Vcs.Branch))
            }
        }
        if (hotfixMerged.isNotEmpty()) {
            hotfixGroup.addSeparator("Merged Versions")
            for (item in hotfixMerged.take(20)) {
                hotfixGroup.add(createCopyAction("${item.version}  (${item.date}, ${item.commitHash})", item.version, AllIcons.Nodes.Tag))
            }
        }
        if (hotfixes.isEmpty()) {
            hotfixGroup.add(createDisabledAction("No hotfix branches found"))
        }
        historyRootGroup.add(hotfixGroup)

        val releaseGroup = DefaultActionGroup("Release History (${releases.size})", true).apply {
            templatePresentation.icon = FlowTagsIcons.Release
        }
        val releaseActive = releases.filter { !it.isFinished }
        val releaseMerged = releases.filter { it.isFinished }
        if (releaseActive.isNotEmpty()) {
            releaseGroup.addSeparator("Active Branches")
            for (item in releaseActive) {
                releaseGroup.add(createCopyAction(item.version, item.version, AllIcons.Vcs.Branch))
            }
        }
        if (releaseMerged.isNotEmpty()) {
            releaseGroup.addSeparator("Merged Versions")
            for (item in releaseMerged.take(20)) {
                releaseGroup.add(createCopyAction("${item.version}  (${item.date}, ${item.commitHash})", item.version, AllIcons.Nodes.Tag))
            }
        }
        if (releases.isEmpty()) {
            releaseGroup.add(createDisabledAction("No release branches found"))
        }
        historyRootGroup.add(releaseGroup)

        val featureGroup = DefaultActionGroup("Feature History (${features.size})", true).apply {
            templatePresentation.icon = FlowTagsIcons.Feature
        }
        val featureActive = features.filter { !it.isFinished }
        val featureMerged = features.filter { it.isFinished }
        if (featureActive.isNotEmpty()) {
            featureGroup.addSeparator("Active Branches")
            for (item in featureActive) {
                featureGroup.add(createCopyAction(item.version, item.version, AllIcons.Vcs.Branch))
            }
        }
        if (featureMerged.isNotEmpty()) {
            featureGroup.addSeparator("Merged Versions")
            for (item in featureMerged.take(20)) {
                featureGroup.add(createCopyAction("${item.version}  (${item.date}, ${item.commitHash})", item.version, AllIcons.Nodes.Tag))
            }
        }
        if (features.isEmpty()) {
            featureGroup.add(createDisabledAction("No feature branches found"))
        }
        historyRootGroup.add(featureGroup)

        rootGroup.add(historyRootGroup)

        val dataContext = DataManager.getInstance().getDataContext(gitFlowButton)
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            "FlowTags Operations & History",
            rootGroup,
            dataContext,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            true
        )
        popup.showUnderneathOf(gitFlowButton)
    }

    fun loadGitVersions() {
        val selectedLimit = limitCombo.selectedItem as? Int ?: 500
        val repositoryManager = GitRepositoryManager.getInstance(project)
        val repositories = repositoryManager.repositories

        if (repositories.isEmpty()) {
            statusLabel.text = "No Git repository found in project."
            tableModel.rowCount = 0
            return
        }

        val settings = GitVersionTrackerSettings.getInstance(project)
        val patternString = settings.state.pattern
        val regex = try {
            Regex(patternString)
        } catch (_: Exception) {
            Regex(GitVersionTrackerSettings.DEFAULT_PATTERN)
        }

        val activeRegex = GitFlowHelper.buildActiveRegex(
            settings.state.hotfixPrefix,
            settings.state.releasePrefix,
            settings.state.featurePrefix
        )

        statusLabel.text = "Loading Git branches & merge commits..."
        refreshButton.isEnabled = false
        pullButton.isEnabled = false

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Loading Git Version History...", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Querying Git branches & logs..."

                val collectedItems = mutableListOf<GitVersionItem>()
                val seenActiveBranches = mutableSetOf<String>()

                for (repo in repositories) {
                    if (indicator.isCanceled) break

                    // 1. 진행 중인(Active) 로컬 브랜치 탐색
                    val repoCurrentBranch = repo.currentBranchName
                    for (branch in repo.branches.localBranches) {
                        val bName = branch.name
                        if (!seenActiveBranches.add(bName)) continue

                        val match = activeRegex.find(bName)
                        if (match != null) {
                            val branchType = match.groupValues[1]
                            val identifier = match.groupValues[2]
                            val isCurr = (bName == repoCurrentBranch)
                            collectedItems.add(
                                GitVersionItem(
                                    type = branchType,
                                    version = identifier,
                                    date = if (isCurr) "Active (Current)" else "Active",
                                    commitHash = if (isCurr) (repo.currentRevision?.take(7) ?: "-") else "-",
                                    rawSubject = if (isCurr) "Current branch ($bName) 🌿" else "Active branch ($bName)",
                                    isFinished = false,
                                    isCurrent = isCurr,
                                    fullBranchName = bName
                                )
                            )
                        }
                    }

                    // 2. 종료된(Merged) 커밋 로그 탐색
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

                                val matchResult = regex.find(subject)
                                if (matchResult != null) {
                                    val branchType = if (matchResult.groupValues.size > 2) matchResult.groupValues[1] else "custom"
                                    val versionString = if (matchResult.groupValues.size > 2) matchResult.groupValues[2] else matchResult.groupValues[1]

                                    collectedItems.add(
                                        GitVersionItem(
                                            type = branchType,
                                            version = versionString,
                                            date = commitDate,
                                            commitHash = commitHash,
                                            rawSubject = subject,
                                            isFinished = true,
                                            isCurrent = false,
                                            fullBranchName = "$branchType/$versionString"
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                ApplicationManager.getApplication().invokeLater {
                    allItems = collectedItems
                    refreshButton.isEnabled = true
                    pullButton.isEnabled = true
                    applyFilter()
                }
            }

            override fun onCancel() {
                ApplicationManager.getApplication().invokeLater {
                    refreshButton.isEnabled = true
                    pullButton.isEnabled = true
                    statusLabel.text = "Query cancelled."
                }
            }

            override fun onThrowable(error: Throwable) {
                ApplicationManager.getApplication().invokeLater {
                    refreshButton.isEnabled = true
                    pullButton.isEnabled = true
                    statusLabel.text = "Error: ${error.message}"
                }
            }
        })
    }

    private fun updateStatusLabelWithBranch(branch: String) {
        ApplicationManager.getApplication().invokeLater {
            val activeCount = allItems.count { !it.isFinished }
            val mergedCount = allItems.count { it.isFinished }
            val latestVer = SemVerHelper.findHighestVersionFromItems(allItems)
            val suggestion = SemVerHelper.suggestNext(latestVer)
            statusLabel.text = "Current: $branch | Items: ${tableModel.rowCount} (Active: $activeCount, Merged: $mergedCount) | Next: hotfix/${suggestion.nextHotfix}, release/${suggestion.nextMinorRelease}"
        }
    }

    private fun applyFilter() {
        val filterType = typeFilterCombo.selectedItem as? String ?: "ALL"
        val filterStatus = statusFilterCombo.selectedItem as? String ?: "ALL"

        val filtered = allItems.filter { item ->
            val matchesType = filterType.equals("ALL", ignoreCase = true) || item.type.equals(filterType, ignoreCase = true)
            val matchesStatus = when (filterStatus) {
                "Active (진행중)" -> !item.isFinished
                "Merged (종료됨)" -> item.isFinished
                else -> true
            }
            matchesType && matchesStatus
        }

        tableModel.rowCount = 0
        for (item in filtered) {
            tableModel.addRow(arrayOf(
                item.type,
                item.statusText,
                item.version,
                item.date,
                item.commitHash,
                item.rawSubject
            ))
        }

        val activeCount = allItems.count { !it.isFinished }
        val mergedCount = allItems.count { it.isFinished }
        val latestVer = SemVerHelper.findHighestVersionFromItems(allItems)
        val suggestion = SemVerHelper.suggestNext(latestVer)
        val currentBranch = GitFlowHelper.getPrimaryRepo(project)?.currentBranchName ?: "HEAD"

        statusLabel.text = "Current: $currentBranch | Showing ${filtered.size} items (Active: $activeCount, Merged: $mergedCount) | Next: hotfix/${suggestion.nextHotfix}, release/${suggestion.nextMinorRelease}"
    }

    private fun copySelectedVersion() {
        val selectedRow = table.selectedRow
        if (selectedRow == -1) {
            notifyMessage("Please select a row to copy.", NotificationType.WARNING)
            return
        }

        val modelRow = table.convertRowIndexToModel(selectedRow)
        val version = tableModel.getValueAt(modelRow, 2) as? String

        if (!version.isNullOrEmpty()) {
            CopyPasteManager.getInstance().setContents(StringSelection(version))
            statusLabel.text = "📋 Copied '$version' to clipboard! (클립보드에 복사되었습니다)"
            notifyMessage("Copied '$version' to clipboard. (클립보드에 복사되었습니다)", NotificationType.INFORMATION)
        }
    }

    private fun notifyMessage(content: String, type: NotificationType) {
        GitFlowHelper.notifyMessage(project, content, type)
    }

    override fun dispose() {
        allItems = emptyList()
    }
}
