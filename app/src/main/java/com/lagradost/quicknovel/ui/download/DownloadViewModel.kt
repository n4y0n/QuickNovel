package com.lagradost.quicknovel.ui.download

import android.content.Context
import android.content.DialogInterface
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.quicknovel.*
import com.lagradost.quicknovel.BaseApplication.Companion.context
import com.lagradost.quicknovel.BaseApplication.Companion.getKey
import com.lagradost.quicknovel.BaseApplication.Companion.getKeys
import com.lagradost.quicknovel.BookDownloader.downloadInfo
import com.lagradost.quicknovel.BookDownloader.hasEpub
import com.lagradost.quicknovel.BookDownloader.remove
import com.lagradost.quicknovel.BookDownloader.turnToEpub
import com.lagradost.quicknovel.BookDownloader2Helper.turnToEpub
import com.lagradost.quicknovel.CommonActivity.activity
import com.lagradost.quicknovel.DataStore.getKey
import com.lagradost.quicknovel.DataStore.getKeys
import com.lagradost.quicknovel.ui.ReadType
import com.lagradost.quicknovel.util.Coroutines.ioSafe
import com.lagradost.quicknovel.util.ResultCached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.internal.toImmutableList

class DownloadViewModel : ViewModel() {
    private val cards: MutableLiveData<ArrayList<DownloadFragment.DownloadDataLoaded>> by lazy {
        MutableLiveData<ArrayList<DownloadFragment.DownloadDataLoaded>>()
    }

    val downloadCards: LiveData<ArrayList<DownloadFragment.DownloadDataLoaded>> = cards

    val normalCards: MutableLiveData<ArrayList<ResultCached>> by lazy {
        MutableLiveData<ArrayList<ResultCached>>()
    }

    val isOnDownloads: MutableLiveData<Boolean> = MutableLiveData(true)
    val currentReadType: MutableLiveData<ReadType?> = MutableLiveData(null)

    var currentSortingMethod: MutableLiveData<Int> =
        MutableLiveData<Int>()

    var currentNormalSortingMethod: MutableLiveData<Int> =
        MutableLiveData<Int>()

    fun refreshCard(card: DownloadFragment.DownloadDataLoaded) {
        BookDownloader2.downloadFromCard(card)
    }

    fun readEpub(card: DownloadFragment.DownloadDataLoaded) = ioSafe {
        try {
            cardsDataMutex.withLock {
                cardsData[card.id]?.generating = true
                cards.postValue(sortArray(ArrayList(cardsData.values)))
            }
            BookDownloader2.readEpub(
                card.id,
                card.downloadedCount,
                card.author,
                card.name,
                card.apiName
            )
        } finally {
            cardsDataMutex.withLock {
                cardsData[card.id]?.generating = false
                cards.postValue(sortArray(ArrayList(cardsData.values)))
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        val values = cardsDataMutex.withLock {
            cardsData.values
        }
        for (card in values) {
            if ((card.downloadedCount * 100 / card.downloadedTotal) > 90) {
                BookDownloader2.downloadFromCard(card)
            }
        }
    }

    fun deleteAlert(card: DownloadFragment.DownloadDataLoaded) {
        val dialogClickListener =
            DialogInterface.OnClickListener { _, which ->
                when (which) {
                    DialogInterface.BUTTON_POSITIVE -> {
                        delete(card)
                    }
                    DialogInterface.BUTTON_NEGATIVE -> {
                    }
                }
            }
        val builder: AlertDialog.Builder = AlertDialog.Builder(activity ?: return)
        builder.setMessage("This will permanently delete ${card.name}.\nAre you sure?").setTitle("Delete")
            .setPositiveButton("Delete", dialogClickListener)
            .setNegativeButton("Cancel", dialogClickListener)
            .show()
    }

    fun delete(card: DownloadFragment.DownloadDataLoaded) {
        BookDownloader2.deleteNovel(card.author,card.name,card.apiName)
    }

    private fun sortArray(
        currentArray: ArrayList<DownloadFragment.DownloadDataLoaded>,
        sortMethod: Int? = null,
    ): ArrayList<DownloadFragment.DownloadDataLoaded> {

        if (sortMethod != null) {
            currentSortingMethod.postValue(sortMethod!!)
        }

        return when (sortMethod ?: currentSortingMethod.value) {
            DEFAULT_SORT -> currentArray
            ALPHA_SORT -> {
                currentArray.sortBy { t -> t.name }
                currentArray
            }

            REVERSE_ALPHA_SORT -> {
                currentArray.sortBy { t -> t.name }
                currentArray.reverse()
                currentArray
            }

            DOWNLOADSIZE_SORT -> {
                currentArray.sortBy { t -> -t.downloadedCount }
                currentArray
            }

            REVERSE_DOWNLOADSIZE_SORT -> {
                currentArray.sortBy { t -> t.downloadedCount }
                currentArray
            }

            DOWNLOADPRECENTAGE_SORT -> {
                currentArray.sortBy { t -> -t.downloadedCount.toFloat() / t.downloadedTotal }
                currentArray
            }

            REVERSE_DOWNLOADPRECENTAGE_SORT -> {
                currentArray.sortBy { t -> t.downloadedCount.toFloat() / t.downloadedTotal }
                currentArray
            }

            LAST_ACCES_SORT -> {
                currentArray.sortBy { t ->
                    -(getKey<Long>(
                        DOWNLOAD_EPUB_LAST_ACCESS,
                        t.id.toString(),
                        0
                    )!!)
                }
                currentArray
            }

            else -> currentArray
        }
    }

    private fun sortNormalArray(
        currentArray: ArrayList<ResultCached>,
        sortMethod: Int? = null,
    ): ArrayList<ResultCached> {

        if (sortMethod != null) {
            currentNormalSortingMethod.postValue(sortMethod!!)
        }

        return when (sortMethod ?: currentNormalSortingMethod.value) {
            DEFAULT_SORT -> currentArray
            ALPHA_SORT -> {
                currentArray.sortBy { t -> t.name }
                currentArray
            }

            REVERSE_ALPHA_SORT -> {
                currentArray.sortBy { t -> t.name }
                currentArray.reverse()
                currentArray
            }

            LAST_ACCES_SORT -> {
                currentArray.sortBy { t ->
                    -(getKey<Long>(
                        DOWNLOAD_EPUB_LAST_ACCESS,
                        t.id.toString(),
                        0
                    )!!)
                }
                currentArray
            }

            else -> currentArray
        }
    }

    fun loadData() = viewModelScope.launch {
        currentSortingMethod.postValue(
            getKey(DOWNLOAD_SETTINGS, DOWNLOAD_SORTING_METHOD, LAST_ACCES_SORT)
                ?: LAST_ACCES_SORT
        )
        currentReadType.postValue(null)
        isOnDownloads.postValue(true)
        cardsDataMutex.withLock {
            cards.postValue(sortArray(ArrayList(cardsData.values)))
        }
    }

    fun loadNormalData(state: ReadType) = viewModelScope.launch {
        currentNormalSortingMethod.postValue(
            getKey(DOWNLOAD_SETTINGS, DOWNLOAD_NORMAL_SORTING_METHOD, LAST_ACCES_SORT)
                ?: LAST_ACCES_SORT
        )

        normalCards.postValue(ArrayList())
        isOnDownloads.postValue(false)
        currentReadType.postValue(state)

        val cards = withContext(Dispatchers.IO) {
            val ids = ArrayList<String>()

            val keys = getKeys(RESULT_BOOKMARK_STATE)
            for (key in keys ?: emptyList()) {
                if (getKey<Int>(key) == state.prefValue) {
                    ids.add(
                        key.replaceFirst(
                            RESULT_BOOKMARK_STATE,
                            RESULT_BOOKMARK
                        )
                    ) // I know kinda spaghetti
                }
            }
            ids.mapNotNull { id -> getKey<ResultCached>(id) }
        }
        normalCards.postValue(sortNormalArray(ArrayList(cards)))
    }

    fun sortData(sortMethod: Int? = null) = ioSafe {
        cardsDataMutex.withLock {
            cards.postValue(sortArray(ArrayList(cardsData.values), sortMethod))
        }
    }

    fun sortNormalData(sortMethod: Int? = null) {
        normalCards.postValue(sortNormalArray(normalCards.value ?: return, sortMethod))
    }

    init {
        BookDownloader2.downloadDataChanged += ::progressDataChanged
        BookDownloader2.downloadProgressChanged += ::progressChanged
        BookDownloader2.downloadDataRefreshed += ::downloadDataRefreshed

        // just in case this runs way after other init that we don't miss downloadDataRefreshed
        downloadDataRefreshed(0)
    }

    override fun onCleared() {
        super.onCleared()
        BookDownloader2.downloadProgressChanged -= ::progressChanged
        BookDownloader2.downloadDataChanged -= ::progressDataChanged
        BookDownloader2.downloadDataRefreshed -= ::downloadDataRefreshed
    }

    private val cardsDataMutex = Mutex()
    private val cardsData: HashMap<Int, DownloadFragment.DownloadDataLoaded> = hashMapOf()

    private fun progressChanged(data: Pair<Int, BookDownloader2Helper.DownloadProgressState>) =
        ioSafe {
            cardsDataMutex.withLock {
                val (id, state) = data
                cardsData[id]?.apply {
                    downloadedCount = state.progress
                    downloadedTotal = state.total
                    this.state = state.state
                    this.ETA = state.eta()
                }
                cards.postValue(sortArray(ArrayList(cardsData.values)))
            }
        }

    private fun progressDataChanged(data: Pair<Int, DownloadFragment.DownloadData>) = ioSafe {
        cardsDataMutex.withLock {
            val (id, value) = data
            cardsData[id]?.apply {
                source = value.source
                name = value.name
                author = value.name
                posterUrl = value.posterUrl
                rating = value.rating
                peopleVoted = value.peopleVoted
                views = value.views
                Synopsis = value.Synopsis
                tags = value.tags
                apiName = value.apiName
            } ?: run {
                cardsData[id] = DownloadFragment.DownloadDataLoaded(
                    value.source,
                    value.name,
                    value.author,
                    value.posterUrl,
                    value.rating,
                    value.peopleVoted,
                    value.views,
                    value.Synopsis,
                    value.tags,
                    value.apiName,
                    0,
                    0,
                    "",
                    BookDownloader2Helper.DownloadState.Nothing,
                    id,
                    false
                )
            }
            cards.postValue(sortArray(ArrayList(cardsData.values)))
        }
    }

    private fun downloadDataRefreshed(_id: Int) = ioSafe {
        BookDownloader2.downloadInfoMutex.withLock {
            cardsDataMutex.withLock {
                BookDownloader2.downloadData.map { (key, value) ->
                    val info = BookDownloader2.downloadProgress[key] ?: return@map
                    cardsData[key] = DownloadFragment.DownloadDataLoaded(
                        value.source,
                        value.name,
                        value.author,
                        value.posterUrl,
                        value.rating,
                        value.peopleVoted,
                        value.views,
                        value.Synopsis,
                        value.tags,
                        value.apiName,
                        info.progress,
                        info.total,
                        info.eta(),
                        info.state,
                        key,
                        false
                    )
                }
                cards.postValue(sortArray(ArrayList(cardsData.values)))
            }
        }
    }
}