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
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
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
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.DocumentEvent

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
        val nextFeature: String = "",
        val baseVersion: String? = null
    )

    data class ParsedSemVer(
        val raw: String,
        val prefix: String,
        val major: Int,
        val minor: Int,
        val patch: Int,
        val delimiter: String = "",
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

    private val SEMVER_REGEX = Regex("""^(v|V)?(\d+)\.(\d+)(?:\.(\d+))?(?:([-_])([0-9a-zA-Z._]+))?$""")
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
            val delimiter = match.groupValues[5]
            val suffix = match.groupValues[6]
            return ParsedSemVer(trimmed, prefix, major, minor, patch, delimiter, suffix)
        }
        val numMatch = PURE_NUMBER_REGEX.find(trimmed)
        if (numMatch != null) {
            val prefix = numMatch.groupValues[1]
            val num = numMatch.groupValues[2].toIntOrNull() ?: 0
            return ParsedSemVer(trimmed, prefix, num, 0, 0, "", "", isNumericOnly = true)
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
        val todayBasic = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) // e.g. 20261014
        if (latestVersion.isNullOrBlank()) {
            return NextVersionSuggestion(
                nextHotfix = "1.0.1-$todayBasic",
                nextMinorRelease = "1.1.0-$todayBasic",
                nextMajorRelease = "2.0.0-$todayBasic",
                nextFeature = "-$todayBasic",
                baseVersion = null
            )
        }

        val parsed = parseVersion(latestVersion)
        if (parsed != null && !parsed.isNumericOnly) {
            val prefix = parsed.prefix
            val major = parsed.major
            val minor = parsed.minor
            val patch = parsed.patch

            // Middle delimiter is always '-' as requested
            val delimiter = "-"

            // Determine today's date format (YYYY-MM-DD vs YYYY.MM.DD vs YYYYMMDD)
            val todayFormatted = when {
                parsed.suffix.matches(Regex(""".*\d{4}-\d{1,2}-\d{1,2}.*""")) -> LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                parsed.suffix.matches(Regex(""".*\d{4}\.\d{1,2}\.\d{1,2}.*""")) -> LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
                else -> todayBasic // e.g. 20261014
            }
            val dateSuffix = "$delimiter$todayFormatted"

            val nextHotfix = "$prefix$major.$minor.${patch + 1}$dateSuffix"
            val nextMinor = "$prefix$major.${minor + 1}.0$dateSuffix"
            val nextMajor = "$prefix${major + 1}.0.0$dateSuffix"

            return NextVersionSuggestion(nextHotfix, nextMinor, nextMajor, dateSuffix, latestVersion)
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

    data class GitRootInfo(
        val rootVirtualFile: VirtualFile,
        val rootFile: File,
        val repo: GitRepository?
    )

    fun findRepoForRoot(repoManager: GitRepositoryManager, vFile: VirtualFile): GitRepository? {
        // 1. Direct in-memory lookup (0ms, 100% EDT safe, no synchronous updates)
        val inMemory = repoManager.repositories.firstOrNull { it.root == vFile || it.root.path == vFile.path }
        if (inMemory != null) return inMemory

        // 2. Only invoke getRepositoryForRoot when running on a background thread
        if (!ApplicationManager.getApplication().isDispatchThread) {
            return try {
                repoManager.getRepositoryForRoot(vFile)
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    fun getAllGitRoots(project: Project): List<GitRootInfo> {
        val results = mutableListOf<GitRootInfo>()
        val seenPaths = mutableSetOf<String>()

        // 1. GitRepositoryManager.repositories (최우선)
        val repoManager = GitRepositoryManager.getInstance(project)
        for (repo in repoManager.repositories) {
            val path = repo.root.path
            if (seenPaths.add(path)) {
                results.add(GitRootInfo(repo.root, File(path), repo))
            }
        }

        // 2. ProjectLevelVcsManager allVcsRoots
        val vcsRoots = ProjectLevelVcsManager.getInstance(project).allVcsRoots
        for (vcsRoot in vcsRoots) {
            val vFile = vcsRoot.path ?: continue
            val path = vFile.path
            if (seenPaths.add(path)) {
                val repo = findRepoForRoot(repoManager, vFile)
                results.add(GitRootInfo(vFile, File(path), repo))
            }
        }

        // 3. Filesystem fallback on project.basePath (멀티루트 서브디렉토리, 서브모듈 감지)
        val basePath = project.basePath
        if (!basePath.isNullOrBlank()) {
            val baseDir = File(basePath)
            val lfs = LocalFileSystem.getInstance()

            fun checkAndAdd(file: File) {
                val gitDir = File(file, ".git")
                if (gitDir.exists() && seenPaths.add(file.absolutePath)) {
                    val vFile = lfs.findFileByIoFile(file) ?: lfs.findFileByPath(file.absolutePath)
                    if (vFile != null) {
                        val repo = findRepoForRoot(repoManager, vFile)
                        results.add(GitRootInfo(vFile, file, repo))
                    }
                }
            }

            // 프로젝트 루트 디렉토리
            checkAndAdd(baseDir)

            // 1-depth 서브디렉토리 (예: sedaily/msp-sedaily, atoo/nsp-atoo 등)
            baseDir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { sub1 ->
                checkAndAdd(sub1)
                // 2-depth 서브디렉토리 (중첩 모듈)
                sub1.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { sub2 ->
                    checkAndAdd(sub2)
                }
            }
        }

        return results
    }

    fun getPrimaryRepo(project: Project): GitRepository? {
        val repoManager = GitRepositoryManager.getInstance(project)
        val inMemory = repoManager.repositories
        if (inMemory.isNotEmpty()) return inMemory.first()

        val roots = getAllGitRoots(project)
        val withRepo = roots.firstOrNull { it.repo != null }
        if (withRepo?.repo != null) return withRepo.repo

        if (!ApplicationManager.getApplication().isDispatchThread) {
            for (root in roots) {
                val r = try { repoManager.getRepositoryForRoot(root.rootVirtualFile) } catch (_: Throwable) { null }
                if (r != null) return r
            }
        }
        return null
    }

    fun getFetchHeadTimestamp(project: Project): Long {
        val roots = getAllGitRoots(project)
        if (roots.isEmpty()) return -1L
        var maxTime = 0L
        for (root in roots) {
            val gitDir = File(root.rootFile, ".git")
            val fetchHead = if (gitDir.isDirectory) {
                File(gitDir, "FETCH_HEAD")
            } else if (gitDir.isFile) {
                val line = try { gitDir.readLines().firstOrNull { it.startsWith("gitdir:") } } catch (_: Throwable) { null }
                if (line != null) {
                    val targetPath = line.substringAfter("gitdir:").trim()
                    val resolved = if (File(targetPath).isAbsolute) File(targetPath) else File(root.rootFile, targetPath)
                    File(resolved, "FETCH_HEAD")
                } else null
            } else null

            if (fetchHead != null && fetchHead.exists()) {
                val t = fetchHead.lastModified()
                if (t > maxTime) maxTime = t
            }
        }
        return maxTime
    }

    fun getCurrentBranchName(project: Project, root: GitRootInfo): String? {
        val repoBranch = root.repo?.currentBranchName
        if (!repoBranch.isNullOrBlank() && repoBranch != "HEAD") {
            return repoBranch
        }

        // Direct .git/HEAD parse (0ms, 100% EDT safe)
        val gitDir = File(root.rootFile, ".git")
        val headFile = if (gitDir.isDirectory) {
            File(gitDir, "HEAD")
        } else if (gitDir.isFile) {
            try {
                val line = gitDir.readLines().firstOrNull { it.startsWith("gitdir:") }
                if (line != null) {
                    val targetPath = line.substringAfter("gitdir:").trim()
                    val resolved = if (File(targetPath).isAbsolute) File(targetPath) else File(root.rootFile, targetPath)
                    File(resolved, "HEAD")
                } else null
            } catch (_: Throwable) { null }
        } else null

        if (headFile != null && headFile.exists()) {
            try {
                val headText = headFile.readText().trim()
                if (headText.startsWith("ref: refs/heads/")) {
                    val b = headText.removePrefix("ref: refs/heads/").trim()
                    if (b.isNotBlank()) return b
                }
            } catch (_: Throwable) {}
        }

        if (!ApplicationManager.getApplication().isDispatchThread) {
            try {
                val handler = GitLineHandler(project, root.rootVirtualFile, GitCommand.REV_PARSE)
                handler.addParameters("--abbrev-ref", "HEAD")
                val res = Git.getInstance().runCommand(handler)
                if (res.success()) {
                    val out = res.outputAsJoinedString.trim()
                    if (out.isNotBlank() && out != "HEAD") return out
                }
            } catch (_: Throwable) {}
        }

        return root.repo?.currentBranchName
    }

    fun getLocalBranches(project: Project, root: GitRootInfo): List<String> {
        val fromRepo = root.repo?.branches?.localBranches?.map { it.name }
        if (!fromRepo.isNullOrEmpty()) {
            return fromRepo
        }

        try {
            val bHandler = GitLineHandler(project, root.rootVirtualFile, GitCommand.BRANCH)
            bHandler.addParameters("--list")
            val bResult = Git.getInstance().runCommand(bHandler)
            if (bResult.success()) {
                val branches = bResult.output.map { it.trim().removePrefix("*").trim() }.filter { it.isNotBlank() }
                if (branches.isNotEmpty()) return branches
            }
        } catch (_: Throwable) {}

        val gitDir = File(root.rootFile, ".git")
        val refsHeads = if (gitDir.isDirectory) File(gitDir, "refs/heads") else null
        if (refsHeads != null && refsHeads.exists()) {
            val branchList = mutableListOf<String>()
            refsHeads.walkTopDown().filter { it.isFile }.forEach { f ->
                val rel = f.relativeTo(refsHeads).path.replace('\\', '/')
                if (rel.isNotBlank()) branchList.add(rel)
            }
            if (branchList.isNotEmpty()) return branchList
        }

        return emptyList()
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

    fun getGitConfigFile(repo: GitRepository): File? {
        val rootPath = repo.root.path
        val gitDirOrFile = File(rootPath, ".git")
        if (!gitDirOrFile.exists()) return null
        if (gitDirOrFile.isDirectory) {
            return File(gitDirOrFile, "config")
        }
        if (gitDirOrFile.isFile) {
            val line = gitDirOrFile.readLines().firstOrNull { it.trim().startsWith("gitdir:") }
            if (line != null) {
                val targetPath = line.substringAfter("gitdir:").trim()
                val resolvedDir = if (File(targetPath).isAbsolute) File(targetPath) else File(rootPath, targetPath)
                return File(resolvedDir, "config")
            }
        }
        return null
    }

    fun readGitConfigFromFile(repo: GitRepository, key: String): String? {
        val parts = key.split('.')
        if (parts.size < 2) return null
        val targetSection = if (parts.size == 2) {
            parts[0].trim().lowercase()
        } else {
            "${parts[0].trim().lowercase()} \"${parts[1].trim()}\""
        }
        val targetKey = parts.last().trim().lowercase()

        val configFile = getGitConfigFile(repo) ?: return null
        if (!configFile.exists() || !configFile.canRead()) return null

        return try {
            var currentSection: String? = null
            var matchedValue: String? = null
            configFile.forEachLine { rawLine ->
                if (matchedValue != null) return@forEachLine
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    // comment or empty
                } else if (line.startsWith("[") && line.endsWith("]")) {
                    val content = line.substring(1, line.length - 1).trim()
                    currentSection = if (content.contains('"')) {
                        val secName = content.substringBefore('"').trim().lowercase()
                        val subName = content.substringAfter('"').substringBeforeLast('"')
                        "$secName \"$subName\""
                    } else {
                        content.lowercase()
                    }
                } else if (currentSection != null && currentSection.equals(targetSection, ignoreCase = true) && line.contains('=')) {
                    val k = line.substringBefore('=').trim().lowercase()
                    if (k == targetKey) {
                        matchedValue = line.substringAfter('=').trim().ifEmpty { null }
                    }
                }
            }
            matchedValue
        } catch (e: Exception) {
            null
        }
    }

    fun readGitConfig(project: Project, repo: GitRepository, key: String): String? {
        // 1. Direct file read: 0ms, zero processes, 100% safe on EDT (fixes BuiltInServerManagerImpl assertion)
        val fromFile = readGitConfigFromFile(repo, key)
        if (fromFile != null) return fromFile

        // 2. If running on EDT, do NOT spawn external git process
        if (ApplicationManager.getApplication().isDispatchThread) {
            return null
        }

        // 3. Background thread fallback: use CLI git config --get
        return try {
            val handler = GitLineHandler(project, repo.root, GitCommand.CONFIG)
            handler.addParameters("--get", key)
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) result.outputAsJoinedString.trim().ifEmpty { null } else null
        } catch (e: Throwable) {
            null
        }
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
        // When on EDT, use in-memory ChangeListManager to avoid blocking EDT or triggering built-in server assertion
        if (ApplicationManager.getApplication().isDispatchThread) {
            val changeListManager = ChangeListManager.getInstance(project)
            return changeListManager.allChanges.isEmpty()
        }

        return try {
            val handler = GitLineHandler(project, repo.root, GitCommand.STATUS)
            handler.addParameters("--porcelain", "--untracked-files=no")
            val result = Git.getInstance().runCommand(handler)
            result.success() && result.output.none { it.isNotBlank() && !it.startsWith("??") }
        } catch (e: Throwable) {
            val changeListManager = ChangeListManager.getInstance(project)
            changeListManager.allChanges.isEmpty()
        }
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
        val roots = getAllGitRoots(project)
        if (roots.isEmpty()) {
            notifyMessage(project, "No Git repository found in project.", NotificationType.ERROR)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Running Git Pull...", false), DumbAware {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Executing git pull..."

                var anySuccess = false
                var lastError: String? = null
                for (root in roots) {
                    val handler = GitLineHandler(project, root.rootVirtualFile, GitCommand.PULL)
                    val result = Git.getInstance().runCommand(handler)
                    try {
                        root.repo?.update()
                    } catch (_: Throwable) {}

                    if (result.success()) {
                        anySuccess = true
                    } else {
                        lastError = result.errorOutputAsJoinedString
                    }
                }

                if (anySuccess) {
                    notifyMessage(project, "Git Pull completed successfully. Versions updated.", NotificationType.INFORMATION)
                    ApplicationManager.getApplication().invokeLater {
                        onFinished?.invoke()
                    }
                } else {
                    notifyMessage(project, "Git Pull failed: ${lastError ?: "Unknown error"}", NotificationType.ERROR)
                }
            }
        })
    }

    fun checkoutBranch(project: Project, branchName: String, onFinished: (() -> Unit)? = null) {
        val repo = getPrimaryRepo(project) ?: run {
            notifyMessage(project, "No Git repository found in project.", NotificationType.ERROR)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Checking out $branchName...", false), DumbAware {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                if (!isWorkingTreeClean(project, repo)) {
                    notifyMessage(project, "Cannot checkout '$branchName': You have uncommitted changes. Please commit or stash them first.", NotificationType.WARNING)
                    return
                }

                val handler = GitLineHandler(project, repo.root, GitCommand.CHECKOUT)
                handler.addParameters(branchName)
                val result = Git.getInstance().runCommand(handler)
                try {
                    repo.update()
                } catch (_: Throwable) {}

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

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Initializing GitFlow...", false), DumbAware {
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

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Starting GitFlow $branchType ($newBranchName)...", false), DumbAware {
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
        deleteRemoteBranch: Boolean = false,
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

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Finishing GitFlow branch '$branchName'...", false), DumbAware {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                if (!isWorkingTreeClean(project, repo)) {
                    notifyMessage(project, "Cannot finish '$branchName': You have uncommitted changes. Please commit or stash them first.", NotificationType.WARNING)
                    return
                }

                val deleteDesc = when {
                    deleteBranch && deleteRemoteBranch -> " and deleted branch (local & remote)"
                    deleteBranch -> " and deleted local branch"
                    deleteRemoteBranch -> " and deleted remote branch"
                    else -> ""
                }

                fun deleteRemoteIfRequested() {
                    if (deleteRemoteBranch && repo.remotes.isNotEmpty()) {
                        val remoteName = repo.branches.findRemoteBranch("origin/$branchName")?.remote?.name
                            ?: repo.branches.remoteBranches.firstOrNull { it.nameForRemoteOperations == branchName }?.remote?.name
                            ?: repo.remotes.firstOrNull()?.name
                            ?: "origin"
                        indicator.text = "Deleting remote branch '$branchName' on $remoteName..."
                        val success = runGit(project, repo, GitCommand.PUSH, remoteName, "--delete", branchName)
                        if (!success) {
                            notifyMessage(
                                project,
                                "Note: Remote branch '$remoteName/$branchName' could not be deleted (it might not exist on remote or requires permissions).",
                                NotificationType.WARNING
                            )
                        }
                    }
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
                        deleteRemoteIfRequested()
                        if (deleteBranch) {
                            indicator.text = "Deleting local branch $branchName..."
                            runGit(project, repo, GitCommand.BRANCH, "-d", branchName)
                        }

                        repo.update()
                        notifyMessage(project, "GitFlow Finish: Merged '$branchName' into '$devBranch'$deleteDesc.", NotificationType.INFORMATION)
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
                        deleteRemoteIfRequested()
                        if (deleteBranch) {
                            indicator.text = "Deleting local branch $branchName..."
                            runGit(project, repo, GitCommand.BRANCH, "-d", branchName)
                        }

                        // 6. Return to target branch
                        val returnBranch = if (isHotfix) prodBranch else devBranch
                        runGit(project, repo, GitCommand.CHECKOUT, returnBranch)

                        repo.update()
                        notifyMessage(project, "GitFlow Finish: '$branchName' merged into $prodBranch & $devBranch${if (createTag) ", tagged as '$tagName'" else ""}$deleteDesc.", NotificationType.INFORMATION)
                        onFinished?.let { ApplicationManager.getApplication().invokeLater(it) }
                    }

                    else -> {
                        // General branch fallback
                        indicator.text = "Checking out $devBranch..."
                        if (runGit(project, repo, GitCommand.CHECKOUT, devBranch)) {
                            val mergeMsg = "Merge branch '$branchName' into $devBranch"
                            runGit(project, repo, GitCommand.MERGE, "--no-ff", "-m", mergeMsg, branchName)
                            deleteRemoteIfRequested()
                            if (deleteBranch) runGit(project, repo, GitCommand.BRANCH, "-d", branchName)
                            repo.update()
                            notifyMessage(project, "Merged '$branchName' into '$devBranch'$deleteDesc.", NotificationType.INFORMATION)
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

        val dialog = GitFlowFinishDialog(project, repo, branchName, prodBranch, devBranch, settings.state.tagPrefix)
        if (dialog.showAndGet()) {
            finishBranch(
                project = project,
                branchName = branchName,
                deleteBranch = dialog.isDeleteBranch(),
                deleteRemoteBranch = dialog.isDeleteRemoteBranch(),
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

    private fun formatWithDate(raw: String): String {
        if (raw.isBlank()) return raw
        val todayBasic = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        val hasDate = raw.matches(Regex(""".*[-_.]\d{8}(?:[-_.].*)?$""")) ||
                      raw.matches(Regex(""".*[-_.]\d{4}[-.]\d{1,2}[-.]\d{1,2}(?:[-_.].*)?$""")) ||
                      raw.matches(Regex("""^\d{8}$"""))
        return if (hasDate) raw else "$raw-$todayBasic"
    }

    private fun updatePreview() {
        val input = identifierField.text.trim()
        val display = if (input.isEmpty()) "..." else formatWithDate(input)
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

    fun getIdentifier(): String = formatWithDate(identifierField.text.trim())

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
    private val repo: GitRepository,
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
    private val deleteLocalCheckbox = JBCheckBox("Delete local branch '$branchName' after merge", true)

    private val remoteBranch = repo.branches.findRemoteBranch("origin/$branchName")
        ?: repo.branches.remoteBranches.firstOrNull { it.nameForRemoteOperations == branchName }
    private val hasRemoteBranch = remoteBranch != null
    private val remoteName = remoteBranch?.remote?.name ?: repo.remotes.firstOrNull()?.name ?: "origin"
    private val hasRemotes = repo.remotes.isNotEmpty()

    private val deleteRemoteCheckbox = JBCheckBox(
        if (hasRemoteBranch) "Delete remote branch ('$remoteName/$branchName') after merge"
        else "Delete remote branch ('$remoteName/$branchName')",
        hasRemoteBranch
    ).apply {
        isEnabled = hasRemotes
        if (!hasRemotes) {
            text = "Delete remote branch (No remote repository configured)"
        }
    }

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
                    "• Clean up feature branch (local / remote)</html>"
            "release" -> "<html>Finish release branch <code><b>$branchName</b></code>:<br/>" +
                    "• Checkout and merge into <b>$prodBranch</b> (<code>--no-ff</code>)<br/>" +
                    "• Prod commit: <code>Merge branch '$branchName' into $prodBranch</code><br/>" +
                    "• Tag release as <b>$tagName</b> (Message: <code>Release $tagName</code>)<br/>" +
                    "• Merge back into <b>$devBranch</b> (<code>--no-ff</code>)<br/>" +
                    "• Clean up release branch (local / remote)</html>"
            "hotfix" -> "<html>Finish hotfix branch <code><b>$branchName</b></code>:<br/>" +
                    "• Checkout and merge into <b>$prodBranch</b> (<code>--no-ff</code>)<br/>" +
                    "• Prod commit: <code>Merge branch '$branchName' into $prodBranch</code><br/>" +
                    "• Tag hotfix as <b>$tagName</b> (Message: <code>Hotfix $tagName</code>)<br/>" +
                    "• Merge back into <b>$devBranch</b> (<code>--no-ff</code>)<br/>" +
                    "• Clean up hotfix branch (local / remote)</html>"
            else -> "<html>Merge branch <code><b>$branchName</b></code> into <b>$devBranch</b> (<code>--no-ff</code>)</html>"
        }

        val descLabel = JBLabel(descHtml)
        val optionsPanel = JPanel(GridLayout(3, 1, 0, 6))
        optionsPanel.add(tagCheckbox)
        optionsPanel.add(deleteLocalCheckbox)
        optionsPanel.add(deleteRemoteCheckbox)

        panel.add(descLabel, BorderLayout.NORTH)
        panel.add(optionsPanel, BorderLayout.CENTER)
        return panel
    }

    fun isCreateTag(): Boolean = tagCheckbox.isSelected
    fun isDeleteBranch(): Boolean = deleteLocalCheckbox.isSelected
    fun isDeleteRemoteBranch(): Boolean = deleteRemoteCheckbox.isSelected && deleteRemoteCheckbox.isEnabled
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
    override fun isEnabledByDefault(): Boolean = true
    override fun isConfigurable(): Boolean = true
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
    private var hasInitialLoaded: Boolean = false
    private var lastKnownRevision: String? = null
    private var isLoading: Boolean = false

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

        // Git 이벤트 리스너: pull / 브랜치 변경 / 커밋 변경 / 초기 레포 감지 시 갱신!
        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { repo ->
            val currentFetchTime = GitFlowHelper.getFetchHeadTimestamp(project)
            val isPull = (lastFetchHeadTimestamp != -1L && currentFetchTime > lastFetchHeadTimestamp)
            lastFetchHeadTimestamp = currentFetchTime

            val branch = repo.currentBranchName ?: "unknown"
            val currentRev = repo.currentRevision
            val isRevChanged = (currentRev != null && currentRev != lastKnownRevision)
            val isBranchChanged = (branch != currentBranchName)
            val needsInitialLoad = !hasInitialLoaded || (activeItems.isEmpty() && mergedItems.isEmpty())

            lastKnownRevision = currentRev
            currentBranchName = branch

            if (isPull || isRevChanged || isBranchChanged || needsInitialLoad) {
                loadGitVersions()
            }
        })

        // VCS 리포지토리 매핑 등록/변경 감지 (프로젝트 오픈 시 Git 레포가 비동기로 로드될 때 발행됨)
        connection.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener {
            loadGitVersions()
        })

        // VCS 설정 변경 감지
        connection.subscribe(ProjectLevelVcsManager.VCS_CONFIGURATION_CHANGED, VcsListener {
            loadGitVersions()
        })

        // 프로젝트 인덱싱/스마트 모드 진입 시 자동 확인
        DumbService.getInstance(project).runWhenSmart {
            loadGitVersions()
        }

        // 1. 즉시 1회 시도 (캐시된 레포가 있으면 즉시 로드)
        loadGitVersions()

        // 2. 프로젝트 오픈 직후 IntelliJ가 Git 저장소를 백그라운드에서 정상 매핑할 수 있도록 1.5초 지연 후 추가 확인
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            ApplicationManager.getApplication().invokeLater {
                if (!project.isDisposed && (!hasInitialLoaded || (activeItems.isEmpty() && mergedItems.isEmpty()))) {
                    loadGitVersions()
                }
            }
        }, 1500L, TimeUnit.MILLISECONDS)
    }

    override fun getComponent(): JComponent = label

    fun loadGitVersions(attempt: Int = 0) {
        if (isLoading) return
        if (project.isDisposed) return

        val gitRoots = GitFlowHelper.getAllGitRoots(project)
        if (gitRoots.isEmpty()) {
            activeItems = emptyList()
            mergedItems = emptyList()
            currentBranchName = "no git"
            hasInitialLoaded = false
            label.toolTipText = "FlowTags - Waiting for Git repository..."
            // 프로젝트 시작 시 비동기로 레포지토리가 로딩되는 동안 지연 재시도 (최대 10회)
            val delayMs = if (attempt < 3) 1000L else 2000L
            if (attempt < 10) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule({
                    ApplicationManager.getApplication().invokeLater {
                        if (!project.isDisposed) {
                            loadGitVersions(attempt + 1)
                        }
                    }
                }, delayMs, TimeUnit.MILLISECONDS)
            }
            return
        }

        hasInitialLoaded = true
        val primaryRoot = gitRoots.firstOrNull()
        if (primaryRoot != null) {
            val detected = GitFlowHelper.getCurrentBranchName(project, primaryRoot)
            if (!detected.isNullOrBlank()) {
                currentBranchName = detected
            }
            lastKnownRevision = primaryRoot.repo?.currentRevision
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

        isLoading = true
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Loading Git Branch Versions...", false
        ), DumbAware {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val collectedActive = mutableListOf<GitVersionItem>()
                val collectedMerged = mutableListOf<GitVersionItem>()
                val seenActiveBranches = mutableSetOf<String>()
                var detectedCurrentBranch = "unknown"

                for (root in gitRoots) {
                    if (indicator.isCanceled) break

                    // Git 저장소 내부 상태 동기화
                    try {
                        root.repo?.update()
                    } catch (_: Throwable) {}

                    val branchName = GitFlowHelper.getCurrentBranchName(project, root)
                    if (!branchName.isNullOrBlank()) {
                        detectedCurrentBranch = branchName
                    }

                    // 1. 진행 중인(Active) 로컬 브랜치 탐색
                    val localBranches = GitFlowHelper.getLocalBranches(project, root)
                    for (bName in localBranches) {
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
                                    commitHash = if (isCurr) (root.repo?.currentRevision?.take(7) ?: "-") else "-",
                                    rawSubject = if (isCurr) "Current branch ($bName) 🌿" else "Active branch ($bName)",
                                    isFinished = false,
                                    isCurrent = isCurr,
                                    fullBranchName = bName
                                )
                            )
                        }
                    }

                    // 2. 종료된(Merged) 커밋 로그 탐색
                    val handler = GitLineHandler(project, root.rootVirtualFile, GitCommand.LOG)
                    handler.addParameters(
                        "-n", "500",
                        "--merges",
                        "--pretty=format:%h%x09%cs%x09%s"
                    )

                    val fallbackMergeRegex = Regex("""(?:^|\s|/|'|")((?:hotfix|release|feature)[0-9a-zA-Z._\-]*/|hotfix|release|feature)/([0-9a-zA-Z._\-]+)""")

                    val result = Git.getInstance().runCommand(handler)
                    if (result.success()) {
                        for (line in result.output) {
                            val parts = line.split('\t', limit = 3)
                            if (parts.size >= 3) {
                                val commitHash = parts[0].trim()
                                val commitDate = parts[1].trim()
                                val subject = parts[2].trim()

                                var branchType = ""
                                var versionString = ""
                                val matchResult = regex.find(subject)
                                if (matchResult != null) {
                                    branchType = if (matchResult.groupValues.size > 2) matchResult.groupValues[1] else "custom"
                                    versionString = if (matchResult.groupValues.size > 2) matchResult.groupValues[2] else matchResult.groupValues[1]
                                } else {
                                    val fb = fallbackMergeRegex.find(subject)
                                    if (fb != null) {
                                        val rawType = fb.groupValues[1].removeSuffix("/").lowercase()
                                        branchType = when {
                                            rawType.contains("hotfix") -> "hotfix"
                                            rawType.contains("release") -> "release"
                                            rawType.contains("feature") -> "feature"
                                            else -> "custom"
                                        }
                                        versionString = fb.groupValues[2].trimEnd('\'', '"', ')')
                                    }
                                }

                                if (branchType.isNotBlank() && versionString.isNotBlank()) {
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

                    // 3. Git Tags 탐색 (태그 기반 릴리즈 지원)
                    try {
                        val tagHandler = GitLineHandler(project, root.rootVirtualFile, GitCommand.TAG)
                        tagHandler.addParameters("-l", "--sort=-creatordate")
                        val tagResult = Git.getInstance().runCommand(tagHandler)
                        if (tagResult.success()) {
                            for (tag in tagResult.output) {
                                val trimmedTag = tag.trim()
                                if (trimmedTag.isNotBlank() && SemVerHelper.parseVersion(trimmedTag) != null) {
                                    if (collectedMerged.none { it.version == trimmedTag }) {
                                        val tType = if (trimmedTag.lowercase().contains("hotfix")) "hotfix" else "release"
                                        collectedMerged.add(
                                            GitVersionItem(
                                                type = tType,
                                                version = trimmedTag,
                                                date = "Tag",
                                                commitHash = "-",
                                                rawSubject = "Tag: $trimmedTag",
                                                isFinished = true,
                                                isCurrent = false,
                                                fullBranchName = trimmedTag
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    } catch (_: Throwable) {}
                }

                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    currentBranchName = detectedCurrentBranch
                    activeItems = collectedActive
                    mergedItems = collectedMerged
                    isLoading = false

                    val latestVer = SemVerHelper.findHighestVersion(activeItems, mergedItems)
                    val suggestion = SemVerHelper.suggestNext(latestVer)
                    label.toolTipText = "FlowTags: $currentBranchName | Next: hotfix/${suggestion.nextHotfix}, release/${suggestion.nextMinorRelease}"
                    myStatusBar?.updateWidget(ID())
                }
            }

            override fun onFinished() {
                isLoading = false
            }

            override fun onCancel() {
                isLoading = false
            }

            override fun onThrowable(error: Throwable) {
                isLoading = false
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
        if (!hasInitialLoaded) {
            loadGitVersions()
        }

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
