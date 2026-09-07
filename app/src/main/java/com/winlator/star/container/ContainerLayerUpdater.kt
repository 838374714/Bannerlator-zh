package com.winlator.star.container

import android.content.Context
import android.util.Log
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.FileUtils
import com.winlator.star.core.ProcessHelper
import com.winlator.star.core.WineInfo
import com.winlator.star.xenvironment.ImageFs
import org.json.JSONObject
import java.io.File

/**
 * In-place Proton/Wine layer swap for an existing container.
 *
 * A container records its layer as the contents entry name (`Proton-11.0-6-arm64ec-4`) and, at
 * creation, receives a COPY of every builtin PE from that layer in system32/syswow64. Nothing
 * refreshes those copies later, so pointing the container at a newer build of the same line needs
 * (a) the `wineVersion` swap, (b) a refresh of the builtin copies (native overrides such as DXVK or
 * FEX/wowbox64 are left alone — see [ContainerManager.refreshCommonDlls]) and (c) the app-side
 * registry-tweak cache cleared so `applyGeneralPatches` re-runs. Wine's own `wine.inf` update runs
 * on the next launch by itself (`.update-timestamp` no longer matches the new layer's `wine.inf`).
 *
 * Before touching anything the config file and the three registry hives are snapshotted under
 * `<container>/.layer-update-backup/<epoch>/` so [revert] can put the container back on the old
 * layer (which stays installed — the updater never removes layers).
 *
 * r1 scope: same `type` + same profile `versionName` (hence same arch), strictly higher `verCode`,
 * installed layers only. TODO(cross-line): moving between lines (11.0-2 → 11.0-6, GE ↔ plain) is
 * deliberately not offered here — registry defaults, DirectAudio support and game fixes differ.
 */
class ContainerLayerUpdater(private val context: Context) {

    data class Snapshot(val dir: File, val oldEntry: String, val newEntry: String, val time: Long)

    /** A parsed contents entry name `<Type>-<versionName>-<verCode>`. */
    private data class Entry(val type: ContentProfile.ContentType, val versionName: String, val verCode: Int)

    /**
     * Entry name of the newest INSTALLED layer of the same line as [container]'s, or null when the
     * container already runs the newest one (or is on the bundled Wine / an unparseable entry).
     * Pure directory scan — no network. [contentsManager] must already be synced.
     */
    fun findNewerInstalled(contentsManager: ContentsManager, container: Container): String? {
        val current = container.wineVersion
        if (WineInfo.isMainWineVersion(current)) return null
        val entry = parseEntry(current) ?: return null
        val candidates = contentsManager.getProfiles(entry.type) ?: return null
        val best = candidates
            .filter { it.verName == entry.versionName && it.verCode > entry.verCode }
            .filter { ContentsManager.getInstallDir(context, it).isDirectory }
            .maxByOrNull { it.verCode } ?: return null
        return ContentsManager.getEntryName(best)
    }

    /** The most recent snapshot taken by [update] for [container], or null. */
    fun latestSnapshot(container: Container): Snapshot? {
        val root = File(container.rootDir, BACKUP_DIR)
        val dirs = root.listFiles { f -> f.isDirectory } ?: return null
        return dirs.mapNotNull { readManifest(it) }.maxByOrNull { it.time }
    }

    /**
     * Whether a Wine session is currently running INSIDE this container. Only one container can be
     * active at a time — `home/xuser` is re-pointed at it by [ContainerManager.activateContainer] —
     * so "this container is the active one" + "wine processes alive" is the running test.
     */
    fun isRunning(container: Container): Boolean {
        val home = container.rootDir.parentFile ?: return false
        val active = File(home, ImageFs.USER)
        val activeName = try {
            active.canonicalFile.name
        } catch (e: Exception) {
            return false
        }
        if (activeName != container.rootDir.name) return false
        return ProcessHelper.listRunningWineProcesses().isNotEmpty()
    }

    /**
     * Move [container] onto [targetEntry]. Returns a one-line user-facing result; failure = the
     * container was NOT modified (all guardrails run before the first write).
     */
    fun update(contentsManager: ContentsManager, container: Container, targetEntry: String): Result<String> {
        val oldEntry = container.wineVersion
        val target = contentsManager.getProfileByEntryName(targetEntry)
            ?: return fail("Layer $targetEntry is not installed")
        val targetDir = ContentsManager.getInstallDir(context, target)
        if (!File(targetDir, "lib/wine").isDirectory) return fail("Layer $targetEntry is incomplete (no lib/wine)")
        val targetParsed = parseEntry(targetEntry) ?: return fail("Unrecognised layer name $targetEntry")
        val oldParsed = parseEntry(oldEntry) ?: return fail("Container is not on a contents layer ($oldEntry)")
        if (oldParsed.type != targetParsed.type || oldParsed.versionName != targetParsed.versionName)
            return fail("Only updates within the same layer line are supported (${oldParsed.versionName})")
        if (targetParsed.verCode <= oldParsed.verCode)
            return fail("$targetEntry is not newer than $oldEntry")
        val targetWine = WineInfo.fromIdentifier(context, contentsManager, targetEntry)
        if (contentsManager.getProfileByEntryName(oldEntry) != null) {
            val oldWine = WineInfo.fromIdentifier(context, contentsManager, oldEntry)
            if (oldWine.isArm64EC != targetWine.isArm64EC || oldWine.isWin64 != targetWine.isWin64)
                return fail("Architecture differs between $oldEntry and $targetEntry")
        }
        if (isRunning(container)) return fail("Close the running session in \"${container.name}\" first")

        val snapshotDir = File(container.rootDir, "$BACKUP_DIR/${System.currentTimeMillis()}")
        if (!snapshotDir.mkdirs()) return fail("Couldn't create the backup folder")
        for (rel in SNAPSHOT_FILES) {
            val src = File(container.rootDir, rel)
            if (src.isFile && !FileUtils.copy(src, File(snapshotDir, rel))) {
                FileUtils.delete(snapshotDir)
                return fail("Couldn't back up $rel")
            }
        }
        writeManifest(snapshotDir, oldEntry, targetEntry)

        container.wineVersion = targetEntry
        CLEARED_EXTRAS.forEach { container.putExtra(it, null) }
        container.saveData()

        val written = ContainerManager.refreshCommonDlls(targetDir, targetWine.isArm64EC, container.rootDir)
        val msg = "Updated \"${container.name}\": $oldEntry -> $targetEntry ($written builtin files refreshed)"
        Log.i(TAG, "$msg; snapshot ${snapshotDir.absolutePath}")
        return Result.success(msg)
    }

    /**
     * Put [container] back on [snapshot]'s old layer: registry hives restored from the snapshot,
     * `wineVersion` reverted, builtin copies refreshed from the OLD layer, tweak cache cleared. The
     * snapshot folder is removed on success. The container's current settings (`.container`) are
     * kept — only the layer field changes; the snapshotted copy stays on disk until the folder goes.
     */
    fun revert(contentsManager: ContentsManager, container: Container, snapshot: Snapshot): Result<String> {
        val old = contentsManager.getProfileByEntryName(snapshot.oldEntry)
            ?: return fail("Layer ${snapshot.oldEntry} is no longer installed — reinstall it to revert")
        val oldDir = ContentsManager.getInstallDir(context, old)
        if (isRunning(container)) return fail("Close the running session in \"${container.name}\" first")

        for (rel in SNAPSHOT_FILES) {
            if (rel == ".container") continue
            val src = File(snapshot.dir, rel)
            if (src.isFile && !FileUtils.copy(src, File(container.rootDir, rel)))
                return fail("Couldn't restore $rel")
        }

        val previous = container.wineVersion
        container.wineVersion = snapshot.oldEntry
        CLEARED_EXTRAS.forEach { container.putExtra(it, null) }
        container.saveData()

        val oldWine = WineInfo.fromIdentifier(context, contentsManager, snapshot.oldEntry)
        val written = ContainerManager.refreshCommonDlls(oldDir, oldWine.isArm64EC, container.rootDir)
        FileUtils.delete(snapshot.dir)
        val msg = "Reverted \"${container.name}\": $previous -> ${snapshot.oldEntry} ($written builtin files refreshed)"
        Log.i(TAG, msg)
        return Result.success(msg)
    }

    private fun fail(reason: String): Result<String> {
        Log.w(TAG, reason)
        return Result.failure(IllegalStateException(reason))
    }

    private fun writeManifest(dir: File, oldEntry: String, newEntry: String) {
        val json = JSONObject()
            .put("oldEntry", oldEntry)
            .put("newEntry", newEntry)
            .put("time", System.currentTimeMillis())
        FileUtils.writeString(File(dir, MANIFEST), json.toString())
    }

    private fun readManifest(dir: File): Snapshot? {
        val file = File(dir, MANIFEST)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(FileUtils.readString(file))
            Snapshot(dir, json.getString("oldEntry"), json.getString("newEntry"), json.getLong("time"))
        } catch (e: Exception) {
            null
        }
    }

    /** Mirrors ContentsManager.getProfileByEntryName's split; Wine/Proton entries only. */
    private fun parseEntry(entryName: String?): Entry? {
        if (entryName.isNullOrEmpty()) return null
        val first = entryName.indexOf('-')
        val last = entryName.lastIndexOf('-')
        if (first <= 0 || last <= first) return null
        val type = ContentProfile.ContentType.getTypeByName(entryName.substring(0, first)) ?: return null
        if (type != ContentProfile.ContentType.CONTENT_TYPE_WINE && type != ContentProfile.ContentType.CONTENT_TYPE_PROTON) return null
        val code = entryName.substring(last + 1).toIntOrNull() ?: return null
        return Entry(type, entryName.substring(first + 1, last), code)
    }

    companion object {
        private const val TAG = "ContainerLayerUpdater"
        const val BACKUP_DIR = ".layer-update-backup"
        private const val MANIFEST = "manifest.json"
        private val SNAPSHOT_FILES = listOf(".container", ".wine/system.reg", ".wine/user.reg", ".wine/userdef.reg")

        // patternVersion: re-runs applyGeneralPatches (app-side registry tweaks) on the next launch.
        // dxwrapper: re-applies the wrapper DLLs — cheap insurance, the refresh never touches them.
        private val CLEARED_EXTRAS = listOf("patternVersion", "dxwrapper")

        /** "v5" for `Proton-11.0-6-arm64ec-5` — the short label used by the badge and menu. */
        fun codeLabel(entryName: String): String = "v" + entryName.substringAfterLast('-')
    }
}
