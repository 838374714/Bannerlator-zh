package com.winlator.star.ui.screens

import android.app.Application
import android.content.Context
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerLayerUpdater
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.ContentsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ContainersViewModel(app: Application) : AndroidViewModel(app) {

    private val _containers = MutableStateFlow<List<Container>>(emptyList())
    val containers: StateFlow<List<Container>> = _containers

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // One-shot user message (e.g. a failed duplicate). Screen shows it then calls messageShown().
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    // Per-container in-place layer update state (see ContainerLayerUpdater). layerUpdates maps a
    // container id to the entry name of a newer INSTALLED build of its layer line; layerSnapshots
    // to the snapshot a previous update left behind (= "Revert" is possible).
    private val _layerUpdates = MutableStateFlow<Map<Int, String>>(emptyMap())
    val layerUpdates: StateFlow<Map<Int, String>> = _layerUpdates

    private val _layerSnapshots = MutableStateFlow<Map<Int, ContainerLayerUpdater.Snapshot>>(emptyMap())
    val layerSnapshots: StateFlow<Map<Int, ContainerLayerUpdater.Snapshot>> = _layerSnapshots

    // Blocking progress text while an update/revert runs (null = idle).
    private val _layerBusy = MutableStateFlow<String?>(null)
    val layerBusy: StateFlow<String?> = _layerBusy

    private var manager: ContainerManager = ContainerManager(app)
    private val layerUpdater = ContainerLayerUpdater(app)

    init {
        refresh()
    }

    fun refresh() {
        manager = ContainerManager(getApplication())
        val list = manager.getContainers().toList()
        _containers.value = list
        scanLayerUpdates(list)
    }

    // Cheap directory scan (installed layers only, no network) off the main thread.
    private fun scanLayerUpdates(list: List<Container>) {
        viewModelScope.launch(Dispatchers.IO) {
            val contents = ContentsManager(getApplication()).apply { syncContents() }
            val updates = HashMap<Int, String>()
            val snapshots = HashMap<Int, ContainerLayerUpdater.Snapshot>()
            for (c in list) {
                layerUpdater.findNewerInstalled(contents, c)?.let { updates[c.id] = it }
                layerUpdater.latestSnapshot(c)
                    ?.takeIf { it.oldEntry != c.wineVersion }
                    ?.let { snapshots[c.id] = it }
            }
            _layerUpdates.value = updates
            _layerSnapshots.value = snapshots
        }
    }

    fun updateLayer(container: Container, targetEntry: String) {
        runLayerJob("Updating layer to ${ContainerLayerUpdater.codeLabel(targetEntry)}…") { contents ->
            layerUpdater.update(contents, container, targetEntry)
        }
    }

    fun revertLayer(container: Container, snapshot: ContainerLayerUpdater.Snapshot) {
        runLayerJob("Reverting layer to ${ContainerLayerUpdater.codeLabel(snapshot.oldEntry)}…") { contents ->
            layerUpdater.revert(contents, container, snapshot)
        }
    }

    private fun runLayerJob(busyText: String, job: (ContentsManager) -> Result<String>) {
        if (_layerBusy.value != null) return
        _layerBusy.value = busyText
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                job(ContentsManager(getApplication()).apply { syncContents() })
            }
            _layerBusy.value = null
            _message.value = result.getOrElse { it.message ?: "Layer update failed" }
            refresh()
        }
    }

    fun messageShown() {
        _message.value = null
    }

    fun duplicate(container: Container, onDone: () -> Unit) {
        _isLoading.value = true
        // duplicateContainerAsync posts its callback (with the new Container, or null on
        // hard failure) on the main Handler internally.
        manager.duplicateContainerAsync(container) { result ->
            _isLoading.value = false
            refresh()
            _message.value = if (result == null) "Couldn't duplicate container" else "Container duplicated"
            onDone()
        }
    }

    fun exportContainer(container: Container, onDone: (exportPath: String?) -> Unit) {
        _isLoading.value = true
        val exportDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Winlator/Backups/Containers"
        )
        manager.exportContainer(container) {
            _isLoading.value = false
            val path = File(exportDir, container.getRootDir().name)
            onDone(if (path.exists()) path.absolutePath else null)
        }
    }

    fun importContainer(dir: File, onDone: () -> Unit) {
        _isLoading.value = true
        manager.importContainer(dir) {
            _isLoading.value = false
            refresh()
            onDone()
        }
    }

    fun availableBackups(): List<File> {
        val backupDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Winlator/Backups/Containers"
        )
        return backupDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
    }

    /** Installed games (shortcuts) belonging to [container] — the source for the per-game backup picker. */
    fun shortcutsFor(container: Container): List<Shortcut> =
        manager.loadShortcuts().filter { it.container.id == container.id }

    fun remove(container: Container, context: Context, onDone: () -> Unit) {
        // Disable any home-screen shortcuts pinned for this container before removing it
        manager.loadShortcuts()
            .filter { it.container == container }
            .forEach { ShortcutsViewModel.disableOnScreen(context, it) }

        _isLoading.value = true
        // removeContainerAsync posts its callback on the main Handler internally
        manager.removeContainerAsync(container) {
            _isLoading.value = false
            refresh()
            onDone()
        }
    }
}
