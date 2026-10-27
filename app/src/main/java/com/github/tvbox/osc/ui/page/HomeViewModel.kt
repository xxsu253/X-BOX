package com.github.tvbox.osc.ui.page

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.api.SortAdjuster
import com.github.tvbox.osc.base.App
import com.github.tvbox.osc.bean.AbsSortXml
import com.github.tvbox.osc.bean.AbsXml
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.bean.MovieSort
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.sourcedata.SourceRuntimeState
import com.github.tvbox.osc.sourcedata.SourceViewModel
import com.github.tvbox.osc.util.BootGuard
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.HistoryHelper
import com.github.tvbox.osc.util.HomeSettings
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.LOG
import com.github.tvbox.osc.util.LocalScriptSource
import com.github.tvbox.osc.util.LanguageManager
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

class HomeViewModel : ViewModel() {
    private fun str(resId: Int, vararg args: Any): String {
        val app = App.getInstance() ?: return ""
        return LanguageManager.localized(app).getString(resId, *args)
    }

    sealed interface PartitionState {
        data object Idle : PartitionState
        data object Loading : PartitionState
        data object Empty : PartitionState
        data object Ready : PartitionState
        data object Error : PartitionState
    }

    data class Partition(
        val sort: MovieSort.SortData,
        val state: PartitionState,
        val videos: List<Movie.Video>,
        val nextPage: Int,
        val maxPage: Int,
    ) {
        companion object {
            const val FIRST_PAGE = 1
        }

        val hasMore: Boolean get() = !(maxPage > 0 && nextPage > maxPage)
    }

    data class Rec(val state: PartitionState, val videos: List<Movie.Video>)

    companion object {
        const val HERO_VIDEO_COUNT = 5
    }

    val currentSource = MutableStateFlow<SourceBean?>(null)
    val sources = MutableStateFlow<List<SourceBean>>(emptyList())
    val subscribeItems = MutableStateFlow<List<SubscribeSource>>(emptyList())
    val activeSubscribeIndex = MutableStateFlow(-1)
    val allSorts = MutableStateFlow<List<MovieSort.SortData>>(emptyList())
    val sorts = MutableStateFlow<List<MovieSort.SortData>>(emptyList())
    val rec = MutableStateFlow(Rec(PartitionState.Loading, emptyList()))
    val partitions = MutableStateFlow<List<Partition>>(emptyList())

    val pageLoading = MutableStateFlow(true)
    private val sortsLoaded = MutableStateFlow(false)
    private val bootReady = MutableStateFlow(false)
    val pageErrorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)

    val sortLoadFailed = MutableStateFlow(false)
    private var sortRetried = false
    private val listRetried = HashSet<String>()

    private val scope = viewModelScope
    private val sortViewModel = SourceViewModel()
    private val actionViewModel = SourceViewModel()
    private val recViewModel = SourceViewModel()
    private val loaders = HashMap<String, PartitionLoader>()
    private val loadSemaphore = Semaphore(2)
    private var loadGeneration = 0
    private var loadingSourceKey: String? = null
    private var configReloading = false
    private var watchdogJob: Job? = null
    private var watchdogGrace = 0

    var activeSortId: String? = null
        private set

    var defaultLiveLaunched = false
    var lastBackTime = 0L

    val actionMessages = MutableSharedFlow<String>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch { sortViewModel.sortResult.flow.collect { onSortResult(it) } }
        scope.launch { recViewModel.sortResult.flow.collect { onRecResult(it) } }
        scope.launch {
            actionViewModel.actionResult.flow.collect { json ->
                val msg = json?.optString("msg").orEmpty()
                if (msg.isNotEmpty()) actionMessages.tryEmit(msg)
            }
        }
        scope.launch {
            AppBootstrap.subscriptionCid.drop(1).collect {
                configReloading = true
                reload()
            }
        }
        sources.value = ApiConfig.get().getSwitchSourceBeanList()
        currentSource.value = ApiConfig.get().getHomeSourceBean()
        refreshSubscribes()
        scope.launch {
            AppBootstrap.state.collect {
                bootReady.value = it is AppBootstrap.Boot.Ready
                if (it is AppBootstrap.Boot.Ready) {
                    configReloading = false
                    loadHome()
                }
            }
        }
        scope.launch {
            combine(bootReady, rec, sortsLoaded) { ready, r, loaded ->
                ready && loaded && r.state != PartitionState.Loading
            }.collect { ready ->
                if (ready && pageLoading.value) {
                    pageLoading.value = false
                }
            }
        }
    }

    override fun onCleared() {
        val staleLoaders = ArrayList(loaders.values)
        loaders.clear()
        staleLoaders.forEach { it.release() }
    }

    fun reload() {
        LOG.i("echo--sort-reload")
        SourceRuntimeState.clearRuntimeCache()
        loadHome()
    }

    fun switchSource(bean: SourceBean) {
        LOG.i("echo--sort-switch: key=${bean.key}")
        ApiConfig.get().setSourceBean(bean)
        currentSource.value = bean
        loadHome()
    }

    /** 刷新站点列表与订阅源列表（不重载首页内容） */
    fun refreshSources() {
        sources.value = ApiConfig.get().getSwitchSourceBeanList()
        refreshSubscribes()
    }

    /**
     * 列表刷新：真正重新读取当前订阅源。
     *
     * 之前只做 sources.value = ApiConfig.get().getSwitchSourceBeanList()，
     * 读的是内存里已解析好的那份——站点管理改动的是磁盘上的 lscript.json，
     * 内存那份没重新解析，所以点刷新看不出任何变化。
     * 这里对本地脚本源重新扫描目录并 parseJson，才拿得到增删后的站点。
     */
    fun refreshSourceList() {
        val url = KV.get(HawkConfig.API_URL, "")
        if (!LocalScriptSource.isLocalScript(url)) {
            refreshSources()
            return
        }
        configReloading = true
        scope.launch(Dispatchers.IO) {
            val json = try {
                LocalScriptSource.buildJson(url)
            } catch (th: Throwable) {
                LOG.e("HomeViewModel", th)
                null
            }
            launch(Dispatchers.Main) {
                if (!json.isNullOrEmpty()) {
                    SourceRuntimeState.clearRuntimeCache()
                    try {
                        ApiConfig.get().parseJson(url, json)
                    } catch (th: Throwable) {
                        LOG.e("HomeViewModel", th)
                    }
                }
                refreshSources()
                // 当前首页站点若已被移除，顺带切到新的首页站点，避免停在空站点上
                val current = currentSource.value
                if (current == null || current.key.isNullOrEmpty() ||
                    sources.value.none { it.key == current.key }
                ) {
                    loadHome()
                }
                configReloading = false
            }
        }
    }

    fun isSubscribeDisabled(item: SubscribeSource): Boolean = BootGuard.isDisabledSource(item.url)

    fun switchSubscribe(item: SubscribeSource) {
        AppBootstrap.switchVodSubscription(item.url)
    }

    fun refreshSubscribes() {
        val items = vodSubscribes()
        val active = KV.get(HawkConfig.API_URL, "")
        subscribeItems.value = items
        activeSubscribeIndex.value = items.indexOfFirst {
            it.url == active || HistoryHelper.isApiLineSourceOf(it.url, active)
        }
    }

    fun loadHome() {
        sources.value = ApiConfig.get().getSwitchSourceBeanList()
        refreshSubscribes()
        val home = ApiConfig.get().getHomeSourceBean()
        loadingSourceKey = if (home.key.isNullOrEmpty()) null else home.key
        LOG.i("echo--sort-loadHome: key=${loadingSourceKey} name=${home.name} srcCount=${sources.value.size}")
        currentSource.value = home
        pageLoading.value = true
        sortsLoaded.value = false
        sortLoadFailed.value = false
        sortRetried = false
        watchdogGrace = 0
        listRetried.clear()
        rec.value = Rec(PartitionState.Loading, emptyList())
        partitions.value = emptyList()
        val staleLoaders = ArrayList(loaders.values)
        loaders.clear()
        staleLoaders.forEach { it.release() }
        loadGeneration++
        armWatchdog()
        sortViewModel.getSort(loadingSourceKey, HomeSettings.current() == HomeSettings.HomeLayout.Horizontal)
    }

    private fun onHomeLoadTimeout() {
        // 切换在线接口时，配置下载/解析本身就可能超过 20s。此时报“加载失败”属于误判：
        // 数据随后到达又会自动显示，用户只会看到一个假失败。重载中额外给几次宽限。
        if (configReloading && watchdogGrace < 3) {
            watchdogGrace++
            armWatchdog()
            return
        }
        val recLoading = rec.value.state == PartitionState.Loading
        val partitionLoading = partitions.value.any { it.state == PartitionState.Loading }
        if (!recLoading && !partitionLoading) return
        if (recLoading) {
            rec.value = Rec(PartitionState.Error, emptyList())
        }
        if (partitionLoading) {
            partitions.value = partitions.value.map { p ->
                if (p.state == PartitionState.Loading) p.copy(state = PartitionState.Error) else p
            }
        }
        pageErrorEvents.tryEmit(
            if (recLoading) str(R.string.home_load_failed)
            else str(R.string.home_load_partial_timeout)
        )
        pageLoading.value = false
    }

    fun retryPartition(partition: Partition) {
        if (partition.state != PartitionState.Error) return
        listRetried.remove(partition.sort.id)
        partitions.value = partitions.value.map {
            if (it.sort.id == partition.sort.id) it.copy(state = PartitionState.Loading) else it
        }
        requestPartition(partition, Partition.FIRST_PAGE)
    }

    fun retrySort() {
        val key = loadingSourceKey ?: return
        LOG.i("echo--sort-manual-retry: key=$key")
        sortLoadFailed.value = false
        sortRetried = true
        rec.value = Rec(PartitionState.Loading, emptyList())
        sortsLoaded.value = false
        pageLoading.value = true
        watchdogGrace = 0
        armWatchdog()
        sortViewModel.getSort(key, HomeSettings.current() == HomeSettings.HomeLayout.Horizontal)
    }

    fun ensureLoaded(sortId: String) {
        activeSortId = sortId
        val current = partitions.value.firstOrNull { it.sort.id == sortId } ?: return
        if (current.state != PartitionState.Idle) return
        partitions.value = partitions.value.map {
            if (it.sort.id == sortId) it.copy(state = PartitionState.Loading) else it
        }
        requestPartition(current, Partition.FIRST_PAGE)
    }

    fun onLayoutChanged() {
        if (HomeSettings.current() != HomeSettings.HomeLayout.Horizontal) return
        val idle = partitions.value.filter { it.state == PartitionState.Idle }
        if (idle.isNotEmpty()) {
            partitions.value = partitions.value.map {
                if (it.state == PartitionState.Idle) it.copy(state = PartitionState.Loading) else it
            }
            idle.forEach { p -> requestPartition(p, Partition.FIRST_PAGE) }
        }
        val key = loadingSourceKey
        val hasRecSort = allSorts.value.any { it.id == "my0" }
        if (key != null && hasRecSort && rec.value.videos.isEmpty() &&
            rec.value.state != PartitionState.Loading
        ) {
            rec.value = Rec(PartitionState.Loading, emptyList())
            recViewModel.getSort(key, true)
        }
    }

    private fun onSortResult(absXml: AbsSortXml?) {
        val key = loadingSourceKey
        if (key == null) {
            LOG.i("echo--sort-null-key: srcName=${currentSource.value?.name} srcCount=${sources.value.size} absXml=${absXml != null} reloading=$configReloading")
            if (configReloading) return
            rec.value = Rec(PartitionState.Empty, emptyList())
            partitions.value = emptyList()
            sorts.value = emptyList()
            allSorts.value = emptyList()
            sortsLoaded.value = true
            return
        }
        if (absXml?.sourceKey != null && absXml.sourceKey != key) {
            LOG.i("echo--sort-stale-drop: key=$key absKey=${absXml.sourceKey}")
            return
        }

        if (absXml != null && absXml.loadFailed) {
            if (!sortRetried) {
                sortRetried = true
                LOG.i("echo--sort-retry: src=$key")
                sortViewModel.getSort(key, HomeSettings.current() == HomeSettings.HomeLayout.Horizontal)
                return
            }
            LOG.i("echo--sort-failed: src=$key")
            sortLoadFailed.value = true
            rec.value = Rec(PartitionState.Empty, emptyList())
            partitions.value = emptyList()
            sorts.value = emptyList()
            allSorts.value = emptyList()
            sortsLoaded.value = true
            return
        }

        LOG.i("echo--sort-result: src=$key hasClasses=${absXml?.classes?.sortList != null} sortSize=${absXml?.classes?.sortList?.size}")
        val sortList = absXml?.classes?.sortList
        val adjusted = if (sortList != null) {
            SortAdjuster.adjustSort(key, sortList, true)
        } else {
            SortAdjuster.adjustSort(key, ArrayList(), true)
        }
        allSorts.value = adjusted

        val recSort = adjusted.firstOrNull { it.id == "my0" }
        if (recSort != null) {
            loadRec(absXml)
        } else {
            rec.value = Rec(PartitionState.Empty, emptyList())
        }

        val visible = adjusted.filter { it.id != "my0" }
        if (visible.isEmpty() && absXml != null && absXml.videoList.isNullOrEmpty()) {
            if (!sortRetried) {
                sortRetried = true
                LOG.i("echo--sort-empty-retry: src=$key sortSize=${absXml.classes?.sortList?.size}")
                val gen = loadGeneration
                scope.launch {
                    delay(2000)
                    if (loadingSourceKey == key && loadGeneration == gen) {
                        sortViewModel.getSort(key, HomeSettings.current() == HomeSettings.HomeLayout.Horizontal)
                    }
                }
                return
            }
            LOG.i("echo--sort-empty-final: src=$key sortSize=${absXml.classes?.sortList?.size}")
            sortLoadFailed.value = true
            rec.value = Rec(PartitionState.Empty, emptyList())
            partitions.value = emptyList()
            sorts.value = emptyList()
            allSorts.value = emptyList()
            sortsLoaded.value = true
            return
        }
        sorts.value = visible
        val vertical = HomeSettings.current() == HomeSettings.HomeLayout.Vertical
        val active = activeSortId?.takeIf { id -> visible.any { it.id == id } } ?: visible.firstOrNull()?.id
        activeSortId = active
        val newPartitions = visible.map { sort ->
            if (vertical && sort.id != active) {
                Partition(sort, PartitionState.Idle, emptyList(), Partition.FIRST_PAGE, 0)
            } else {
                Partition(sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
            }
        }
        partitions.value = newPartitions
        LOG.i("echo--sort-partitions: n=${newPartitions.size}")
        sortsLoaded.value = true
        newPartitions
            .filter { it.state == PartitionState.Loading }
            .forEach { p -> requestPartition(p, Partition.FIRST_PAGE) }
    }

    private fun loadRec(absXml: AbsSortXml?) {
        val videos = absXml?.videoList ?: emptyList()
        rec.value = if (videos.isEmpty()) Rec(PartitionState.Empty, videos) else Rec(PartitionState.Ready, videos)
    }

    private fun onRecResult(absXml: AbsSortXml?) {
        val key = loadingSourceKey ?: return
        if (absXml?.sourceKey != null && absXml.sourceKey != key) return
        loadRec(absXml)
    }

    private class LoaderResult(val stale: Boolean, val absXml: AbsXml?)

    private fun armWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            delay(20_000)
            onHomeLoadTimeout()
        }
    }

    private fun requestPartition(current: Partition, page: Int) {
        val generation = loadGeneration
        val sourceKey = loadingSourceKey
        val loader = loaders.getOrPut(current.sort.id.orEmpty()) { PartitionLoader(sourceKey) }
        scope.launch {
            loadSemaphore.withPermit {
                if (generation != loadGeneration || loader.released || loader.sourceKey != loadingSourceKey) return@withPermit
                armWatchdog()
                val result = suspendCancellableCoroutine<LoaderResult> { cont ->
                    loader.request(page, current.sort) { r -> if (cont.isActive) cont.resume(r) }
                }
                if (!result.stale) {
                    applyPartitionResult(current.sort.id.orEmpty(), page, result.absXml)
                }
            }
        }
    }

    private fun applyPartitionResult(sortId: String, page: Int, absXml: AbsXml?) {
        if (absXml == null && page == Partition.FIRST_PAGE) {
            if (listRetried.add(sortId)) {
                LOG.i("echo--list-retry: sort=$sortId pg=$page")
                val current = partitions.value.firstOrNull { it.sort.id == sortId } ?: return
                requestPartition(current, Partition.FIRST_PAGE)
            } else {
                LOG.i("echo--list-failed: sort=$sortId pg=$page")
                partitions.value = partitions.value.map { p ->
                    if (p.sort.id == sortId) p.copy(state = PartitionState.Error) else p
                }
            }
            return
        }
        val videos = absXml?.movie?.videoList ?: emptyList()
        LOG.i("echo--list-result: src=${loadingSourceKey} sort=$sortId pg=$page n=${videos.size}")
        val maxPage = absXml?.movie?.pagecount ?: 0
        partitions.value = partitions.value.map { p ->
            if (p.sort.id != sortId) {
                p
            } else if (videos.isEmpty() && page == Partition.FIRST_PAGE) {
                Partition(p.sort, PartitionState.Empty, emptyList(), Partition.FIRST_PAGE, maxPage)
            } else {
                val merged = if (page == 0) videos else p.videos + videos
                Partition(p.sort, PartitionState.Ready, merged, page + 1, maxPage)
            }
        }
        fillRecFromPartition()
    }

    private fun fillRecFromPartition() {
        if (rec.value.videos.isNotEmpty()) return
        val first = partitions.value.firstOrNull {
            it.state == PartitionState.Ready && it.videos.isNotEmpty()
        } ?: return
        rec.value = Rec(PartitionState.Ready, first.videos.take(HERO_VIDEO_COUNT))
    }

    fun loadMorePartition(partition: Partition) {
        if (partition.state != PartitionState.Ready || !partition.hasMore) return
        val loader = loaders[partition.sort.id] ?: return
        if (loader.busy) return
        requestPartition(partition, partition.nextPage)
    }

    fun applyFilter(partition: Partition, filterSelect: Map<String, String>) {
        partition.sort.filterSelect = HashMap(filterSelect)
        partitions.value = partitions.value.map {
            if (it.sort.id == partition.sort.id) {
                Partition(it.sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
            } else {
                it
            }
        }
        requestPartition(partition.copy(sort = partition.sort), Partition.FIRST_PAGE)
    }

    fun handleAction(video: Movie.Video) {
        actionViewModel.action(video.sourceKey, video.action)
    }

    fun refreshPartitions() {
        val vertical = HomeSettings.current() == HomeSettings.HomeLayout.Vertical
        val active = activeSortId
        val targets = if (vertical) {
            partitions.value.filter { it.sort.id == active }
        } else {
            partitions.value
        }
        partitions.value = partitions.value.map { p ->
            if (targets.any { it.sort.id == p.sort.id }) {
                Partition(p.sort, PartitionState.Loading, emptyList(), Partition.FIRST_PAGE, 0)
            } else {
                p
            }
        }
        targets.forEach { p -> requestPartition(p, Partition.FIRST_PAGE) }
    }

    // 注意：loader 不能缓存 sort——applyFilter 是直接改 partition.sort.filterSelect，
    // 请求必须每次都用当前 partition 的 sort，否则筛选值可能发不出去、列表看着像没反应。
    private inner class PartitionLoader(val sourceKey: String?) {
        private val svm = SourceViewModel()
        @Volatile
        private var pending: ((LoaderResult) -> Unit)? = null

        @Volatile
        var busy: Boolean = false
            private set

        @Volatile
        var released: Boolean = false
            private set

        private val observeScope = CoroutineScope(
            SupervisorJob(viewModelScope.coroutineContext[Job]) + Dispatchers.Main.immediate
        )

        init {
            observeScope.launch {
                svm.listResult.flow.collect { abs ->
                    val current = pending
                    pending = null
                    busy = false
                    current?.invoke(LoaderResult(stale = false, absXml = abs))
                }
            }
        }

        fun request(page: Int, sort: MovieSort.SortData, onDone: (LoaderResult) -> Unit) {
            pending?.invoke(LoaderResult(stale = true, absXml = null))
            pending = onDone
            busy = true
            svm.getList(sourceKey, sort, page)
        }

        fun release() {
            released = true
            pending?.invoke(LoaderResult(stale = true, absXml = null))
            pending = null
            busy = false
            observeScope.cancel()
        }
    }
}
