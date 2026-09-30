package com.example.hawk_wallet.ui.screens.support

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.hawk_wallet.data.repository.ApiResult
import com.example.hawk_wallet.data.repository.SupportAttachmentKind
import com.example.hawk_wallet.data.repository.SupportChatRepository
import com.example.hawk_wallet.data.repository.SupportUpload
import com.example.hawk_wallet.ui.screens.orders.HTTP_UNAUTHORIZED
import com.example.hawk_wallet.ui.screens.orders.SESSION_EXPIRED
import com.example.hawk_wallet.ui.screens.orders.code
import com.example.hawk_wallet.ui.screens.orders.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

/** Messages per request (backend default 50, max 100). */
private const val PAGE_SIZE = 50

/** Polling for new messages while the chat is on screen and the conversation is open. */
internal const val POLL_INTERVAL_MS = 2_500L

/** The member's conversation (status) is re-read every Nth poll (~10 s). */
private const val CONVERSATION_POLL_EVERY = 4

/** At most this many `after` pages per poll, so a backlog can never loop forever. */
private const val MAX_POLL_PAGES = 5

/** Decoded images kept in memory; older ones are dropped and re-fetched if shown again. */
private const val MAX_CACHED_IMAGES = 40
private const val MAX_IMAGE_SIDE = 2048

private const val OPEN_FILE_FAILED = "We couldn't open this file. Please try again."
private const val READ_ONLY_MESSAGE = "You can view this conversation but not reply to it."

/** One-shot effects the screen must perform. */
sealed interface SupportChatEvent {
    /** Open a downloaded attachment (in the app's private cache) with another app. */
    data class OpenFile(val file: File, val contentType: String) : SupportChatEvent
}

/**
 * The chat screen for both sides (see [SupportChatSource]). Messages load
 * with the backend's cursors: the latest page first, older pages with
 * `before` as the user scrolls up, and new messages with `after` while the
 * screen is visible. Read markers only move when the screen reports what
 * was actually shown ([onMessagesSeen]). Nothing is computed about
 * balances or orders here - chat is support only.
 */
class SupportChatViewModel(
    private val source: SupportChatSource,
    private val repository: SupportChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SupportChatUiState())
    val uiState: StateFlow<SupportChatUiState> = _uiState

    private val _events = Channel<SupportChatEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var pendingUpload: SupportUpload? = null

    /** Newest message the viewer is known to have read (null = none / unknown). */
    private var readUpToId: String? = null
    private var markReadInFlight = false
    private var pollCount = 0
    private var viewerIsSuperAdmin = false

    init {
        load()
    }

    // --- conversation -------------------------------------------------------------

    /** Loads (or retries loading) the conversation, then its latest messages. */
    fun load() {
        _uiState.update { it.copy(phase = SupportChatPhase.LOADING, loadError = null) }
        viewModelScope.launch {
            when (val result = source.conversation()) {
                is ApiResult.Success -> {
                    val conversation = result.data
                    if (conversation == null) {
                        _uiState.update { it.copy(phase = SupportChatPhase.NO_CONVERSATION, conversation = null) }
                    } else {
                        applyConversation(conversation)
                        _uiState.update { it.copy(phase = SupportChatPhase.READY) }
                        loadLatest()
                    }
                }
                is ApiResult.Error -> {
                    if (result.statusCode == HTTP_UNAUTHORIZED) _uiState.update { it.copy(sessionExpired = true) }
                    _uiState.update { it.copy(phase = SupportChatPhase.LOAD_FAILED, loadError = result.userMessage()) }
                }
            }
        }
    }

    /**
     * Member only, and only on an explicit tap: POST /conversation/, which
     * returns the OPEN conversation or a newly routed one (the backend
     * chooses who answers; the member never sees it).
     */
    fun startConversation() {
        if (_uiState.value.isStarting) return
        _uiState.update { it.copy(isStarting = true, startError = null) }
        viewModelScope.launch {
            when (val result = source.start()) {
                is ApiResult.Success -> {
                    val isNew = result.data.id != _uiState.value.conversation?.id
                    if (isNew) {
                        readUpToId = null
                        _uiState.update {
                            it.copy(messages = emptyList(), hasOlder = false, messagesError = null, images = emptyMap())
                        }
                    }
                    applyConversation(result.data)
                    _uiState.update {
                        it.copy(isStarting = false, phase = SupportChatPhase.READY, composerError = null)
                    }
                    loadLatest()
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(
                        isStarting = false,
                        startError = result.userMessage(),
                        sessionExpired = it.sessionExpired || result.statusCode == HTTP_UNAUTHORIZED
                    )
                }
            }
        }
    }

    /** Admin: the screen passes the session role; a SUPER_ADMIN is always read-only. */
    fun setViewerIsSuperAdmin(isSuperAdmin: Boolean) {
        viewerIsSuperAdmin = isSuperAdmin
        _uiState.update { it.copy(readOnly = it.readOnly || isSuperAdmin) }
    }

    /** Admin only: close the assigned conversation (the backend refuses anyone else). */
    fun closeConversation() {
        val state = _uiState.value
        if (!state.canClose || state.isClosing) return
        _uiState.update { it.copy(isClosing = true, notice = null) }
        viewModelScope.launch {
            when (val result = source.close()) {
                is ApiResult.Success -> {
                    applyConversation(result.data)
                    _uiState.update { it.copy(isClosing = false, notice = "Conversation closed.") }
                }
                is ApiResult.Error -> {
                    handleActionError(result)
                    _uiState.update { it.copy(isClosing = false, notice = it.notice ?: result.userMessage()) }
                }
            }
        }
    }

    fun dismissNotice() = _uiState.update { it.copy(notice = null) }

    private fun applyConversation(conversation: SupportConversationUi) {
        _uiState.update {
            it.copy(
                conversation = conversation,
                readOnly = viewerIsSuperAdmin || !conversation.isAssignedToMe
            )
        }
    }

    // --- messages -----------------------------------------------------------------

    /** The latest page; also the retry after a failed first load. */
    fun loadLatest() {
        if (_uiState.value.isLoadingMessages) return
        _uiState.update { it.copy(isLoadingMessages = true, messagesError = null) }
        viewModelScope.launch {
            when (val result = source.messages(limit = PAGE_SIZE)) {
                is ApiResult.Success -> {
                    val page = result.data
                    _uiState.update {
                        it.copy(
                            isLoadingMessages = false,
                            messages = merge(it.messages, page.messages),
                            hasOlder = if (it.messages.isEmpty()) page.hasMore else it.hasOlder
                        )
                    }
                    initReadMarker()
                }
                is ApiResult.Error -> {
                    handleActionError(result)
                    _uiState.update { it.copy(isLoadingMessages = false, messagesError = result.userMessage()) }
                }
            }
        }
    }

    /** Called when the user scrolls to the oldest loaded message. */
    fun loadOlder() {
        val state = _uiState.value
        val oldest = state.messages.firstOrNull() ?: return
        if (!state.hasOlder || state.isLoadingOlder || state.phase != SupportChatPhase.READY) return
        _uiState.update { it.copy(isLoadingOlder = true) }
        viewModelScope.launch {
            when (val result = source.messages(before = oldest.id, limit = PAGE_SIZE)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoadingOlder = false,
                        messages = merge(it.messages, result.data.messages),
                        hasOlder = result.data.hasMore
                    )
                }
                is ApiResult.Error -> {
                    handleActionError(result)
                    _uiState.update { it.copy(isLoadingOlder = false, messagesError = result.userMessage()) }
                }
            }
        }
    }

    /**
     * Runs while the chat is visible (the screen starts it on resume and
     * cancels it in the background): fetches only messages newer than the
     * last one shown - once straight away, since messages may have arrived
     * while the screen was hidden, then every [POLL_INTERVAL_MS] while the
     * conversation is open. Stops for good once the session expires.
     */
    suspend fun pollWhileVisible() {
        while (true) {
            val state = _uiState.value
            if (state.sessionExpired) return
            if (state.phase == SupportChatPhase.READY && !state.isSending && !state.isClosed) pollOnce()
            delay(POLL_INTERVAL_MS)
        }
    }

    /** One refresh; also the Retry action when a refresh failed. */
    fun refreshNow() {
        viewModelScope.launch { pollOnce() }
    }

    /** Only one refresh at a time (poll loop or Retry); an overlapping call is skipped. */
    private val pollLock = Mutex()

    private suspend fun pollOnce() {
        if (!pollLock.tryLock()) return
        try {
            pollOnceLocked()
        } finally {
            pollLock.unlock()
        }
    }

    private suspend fun pollOnceLocked() {
        val state = _uiState.value
        if (state.phase != SupportChatPhase.READY || state.isLoadingMessages) return
        if (state.messages.isEmpty()) {
            loadLatest()
        } else {
            var after = state.messages.last().id
            for (page in 1..MAX_POLL_PAGES) {
                when (val result = source.messages(after = after, limit = PAGE_SIZE)) {
                    is ApiResult.Success -> {
                        val newer = result.data.messages
                        _uiState.update { it.copy(messages = merge(it.messages, newer), messagesError = null) }
                        if (!result.data.hasMore || newer.isEmpty()) break
                        after = newer.last().id
                    }
                    is ApiResult.Error -> {
                        handleActionError(result)
                        _uiState.update { it.copy(messagesError = result.userMessage()) }
                        return
                    }
                }
            }
        }
        // The member's status can change (an admin closes it); the admin's
        // detail is not re-read, since the backend audits SUPER_ADMIN views.
        pollCount += 1
        if (source.pollsConversation && pollCount % CONVERSATION_POLL_EVERY == 0) {
            val result = source.conversation()
            if (result is ApiResult.Success) result.data?.let { latest ->
                if (latest.id == _uiState.value.conversation?.id) applyConversation(latest)
            }
        }
    }

    // --- read markers ---------------------------------------------------------------

    /**
     * Where the backend's marker already is, from the conversation's
     * unread_count: everything before the oldest unread incoming message.
     */
    private fun initReadMarker() {
        if (readUpToId != null) return
        val state = _uiState.value
        val unread = state.conversation?.unreadCount ?: 0
        val messages = state.messages
        if (messages.isEmpty()) return
        if (unread <= 0) {
            readUpToId = messages.last().id
            return
        }
        val incomingIndexes = messages.indices.filter { messages[it].isFromOtherSide }
        val firstUnread = incomingIndexes.takeLast(unread).firstOrNull() ?: return
        readUpToId = if (firstUnread > 0) messages[firstUnread - 1].id else null
    }

    /**
     * The screen reports the newest message it has actually displayed. The
     * marker moves only forward, only for the viewer's own conversation
     * (never read-only), and only if an unseen incoming message is covered.
     */
    fun onMessagesSeen(newestVisibleId: String) {
        val state = _uiState.value
        if (state.readOnly || state.sessionExpired || state.phase != SupportChatPhase.READY || markReadInFlight) return
        val messages = state.messages
        val seenIndex = messages.indexOfFirst { it.id == newestVisibleId }
        if (seenIndex < 0) return
        val readIndex = readUpToId?.let { id -> messages.indexOfFirst { it.id == id } } ?: -1
        if (seenIndex <= readIndex) return
        val coversUnseenIncoming = (readIndex + 1..seenIndex).any { messages[it].isFromOtherSide }
        if (!coversUnseenIncoming) {
            readUpToId = newestVisibleId
            return
        }
        markReadInFlight = true
        viewModelScope.launch {
            when (val result = source.markRead(newestVisibleId)) {
                is ApiResult.Success -> {
                    readUpToId = newestVisibleId
                    _uiState.update { s -> s.copy(conversation = s.conversation?.copy(unreadCount = 0)) }
                }
                is ApiResult.Error -> handleActionError(result)
            }
            markReadInFlight = false
        }
    }

    // --- composer -------------------------------------------------------------------

    fun onDraftChange(value: String) {
        _uiState.update { it.copy(draft = value.take(SUPPORT_TEXT_MAX_LENGTH), composerError = null) }
    }

    /** A URI from the system picker; read and checked on the device before it can be sent. */
    fun onAttachmentPicked(uri: String, kind: SupportAttachmentKind) {
        if (!_uiState.value.canCompose) return
        _uiState.update { it.copy(isPreparingAttachment = true, composerError = null) }
        viewModelScope.launch {
            when (val result = repository.readAttachment(uri, kind)) {
                is ApiResult.Success -> {
                    val upload = result.data
                    val preview = if (kind == SupportAttachmentKind.IMAGE) decodeFile(upload.file, 480) else null
                    pendingUpload?.let { repository.discardAttachment(it) }
                    pendingUpload = upload
                    _uiState.update {
                        it.copy(
                            isPreparingAttachment = false,
                            pending = PendingAttachmentUi(kind, upload.fileName, upload.size, preview)
                        )
                    }
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isPreparingAttachment = false, composerError = result.message)
                }
            }
        }
    }

    fun removePendingAttachment() {
        pendingUpload?.let { viewModelScope.launch { repository.discardAttachment(it) } }
        pendingUpload = null
        _uiState.update { it.copy(pending = null, composerError = null) }
    }

    /** Sends the text, or the attachment with the text as its caption. Kept on failure for a retry. */
    fun send() {
        val state = _uiState.value
        if (!state.canSend) return
        val text = state.draft.trim()
        val upload = pendingUpload
        _uiState.update { it.copy(isSending = true, composerError = null) }
        viewModelScope.launch {
            val result = if (upload != null) source.sendAttachment(upload, text) else source.sendText(text)
            when (result) {
                is ApiResult.Success -> {
                    upload?.let { repository.discardAttachment(it) }
                    pendingUpload = null
                    _uiState.update {
                        it.copy(
                            isSending = false,
                            draft = "",
                            pending = null,
                            messages = merge(it.messages, listOf(result.data))
                        )
                    }
                }
                is ApiResult.Error -> {
                    handleActionError(result)
                    val detail = result.fieldErrors["file"]?.firstOrNull()
                        ?: result.fieldErrors["text"]?.firstOrNull()
                    _uiState.update {
                        it.copy(isSending = false, composerError = if (it.isClosed || it.readOnly) null else detail ?: result.userMessage())
                    }
                }
            }
        }
    }

    // --- attachments ----------------------------------------------------------------

    /** Loads an image message's picture through the authorized endpoint (once). */
    fun requestImage(messageId: String) {
        val current = _uiState.value.images[messageId]
        if (current is SupportImageState.Loading || current is SupportImageState.Loaded) return
        _uiState.update { it.copy(images = withImage(it.images, messageId, SupportImageState.Loading)) }
        viewModelScope.launch {
            val image = when (val result = source.attachment(messageId)) {
                is ApiResult.Success -> decode(result.data, MAX_IMAGE_SIDE)
                    ?.let { SupportImageState.Loaded(it) }
                    ?: SupportImageState.Failed
                is ApiResult.Error -> {
                    handleActionError(result)
                    SupportImageState.Failed
                }
            }
            _uiState.update { it.copy(images = withImage(it.images, messageId, image)) }
        }
    }

    /** Downloads a FILE attachment through the authorized endpoint and asks the screen to open it. */
    fun openFile(message: SupportMessageUi) {
        val attachment = message.attachment ?: return
        if (_uiState.value.openingFileId != null) return
        _uiState.update { it.copy(openingFileId = message.id, notice = null) }
        viewModelScope.launch {
            when (val result = source.attachment(message.id)) {
                is ApiResult.Success -> {
                    val file = try {
                        repository.saveForViewing(result.data, attachment.name)
                    } catch (e: Exception) {
                        null
                    }
                    if (file != null) {
                        _events.send(SupportChatEvent.OpenFile(file, attachment.contentType))
                        _uiState.update { it.copy(openingFileId = null) }
                    } else {
                        _uiState.update { it.copy(openingFileId = null, notice = OPEN_FILE_FAILED) }
                    }
                }
                is ApiResult.Error -> {
                    handleActionError(result)
                    _uiState.update {
                        it.copy(
                            openingFileId = null,
                            notice = if (result.statusCode == HTTP_UNAUTHORIZED) SESSION_EXPIRED else OPEN_FILE_FAILED
                        )
                    }
                }
            }
        }
    }

    /** The device has no app that can open the file. */
    fun onNoViewerApp() = _uiState.update { it.copy(notice = "No app on this device can open this file.") }

    override fun onCleared() {
        // A picked attachment that was never sent: remove its private cache copy.
        pendingUpload?.file?.delete()
        pendingUpload = null
    }

    // --- helpers --------------------------------------------------------------------

    /** Reacts to errors that change what the screen may do, whatever the action was. */
    private fun handleActionError(error: ApiResult.Error) {
        when {
            error.statusCode == HTTP_UNAUTHORIZED -> _uiState.update { it.copy(sessionExpired = true) }
            error.code == "conversation_closed" -> _uiState.update {
                it.copy(conversation = it.conversation?.copy(isOpen = false))
            }
            error.code == "conversation_read_only" -> _uiState.update {
                it.copy(readOnly = true, notice = READ_ONLY_MESSAGE)
            }
        }
    }

    /** Union by id, oldest first by the backend's timestamps; ties keep the backend's order. */
    private fun merge(current: List<SupportMessageUi>, incoming: List<SupportMessageUi>): List<SupportMessageUi> {
        if (incoming.isEmpty()) return current
        val byId = LinkedHashMap<String, SupportMessageUi>()
        current.forEach { byId[it.id] = it }
        incoming.forEach { byId[it.id] = it }
        return byId.values.sortedBy { sortKey(it.createdAt) }
    }

    /**
     * The backend sends UTC ISO-8601 ("...T10:00:00.123456Z"), omitting the
     * fraction when it is zero; padding it makes the strings sort by time.
     */
    private fun sortKey(timestamp: String): String {
        val zoneStart = timestamp.indexOfAny(charArrayOf('Z', '+'), startIndex = 19).let { if (it < 0) timestamp.length else it }
        val main = timestamp.substring(0, zoneStart)
        val withFraction = if ('.' in main) main else "$main.0"
        val (seconds, fraction) = withFraction.split('.', limit = 2)
        return seconds + "." + fraction.padEnd(6, '0')
    }

    private fun withImage(
        images: Map<String, SupportImageState>,
        id: String,
        state: SupportImageState
    ): Map<String, SupportImageState> {
        val next = LinkedHashMap(images)
        next.remove(id)
        next[id] = state
        while (next.size > MAX_CACHED_IMAGES) next.remove(next.keys.first())
        return next
    }

    /** Downsampled preview of a picked attachment's cache copy. */
    private suspend fun decodeFile(file: File, maxSide: Int) = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun decode(bytes: ByteArray, maxSide: Int) = withContext(Dispatchers.Default) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}
