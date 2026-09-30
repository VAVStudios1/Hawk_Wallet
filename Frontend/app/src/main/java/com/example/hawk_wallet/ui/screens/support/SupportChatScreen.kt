package com.example.hawk_wallet.ui.screens.support

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.hawk_wallet.data.repository.SupportAttachmentKind
import com.example.hawk_wallet.ui.components.HawkBackTopBar
import com.example.hawk_wallet.ui.screens.orders.CenteredLoading
import com.example.hawk_wallet.ui.screens.orders.CenteredMessage
import com.example.hawk_wallet.ui.screens.orders.PrimaryActionButton
import com.example.hawk_wallet.ui.screens.orders.ScreenHorizontalPadding
import com.example.hawk_wallet.ui.theme.Hawk_WalletTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.io.File

/** How long a message must stay on screen before it counts as seen. */
private const val SEEN_DEBOUNCE_MS = 700L

/**
 * Member Support: "How can we help?" until the member taps Start
 * Conversation, then the conversation itself. The other side is always
 * "Hawk Wallet Support"; who answers is decided by the backend.
 */
@Composable
fun SupportChatScreen(viewModel: SupportChatViewModel, onBackClick: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    SupportChatContent(
        uiState = uiState,
        onBackClick = onBackClick,
        onStart = viewModel::startConversation,
        onRetryLoad = viewModel::load
    ) { listState ->
        SupportConversationPane(
            viewModel = viewModel,
            uiState = uiState,
            listState = listState,
            emptyTitle = "Start a conversation",
            emptyMessage = "Send us a message and our support team will help you.",
            closedAction = {
                // The backend's next start creates a new conversation; the closed one never takes messages.
                PrimaryActionButton(
                    text = "Start New Conversation",
                    onClick = viewModel::startConversation,
                    loading = uiState.isStarting
                )
            }
        )
    }
}

@Composable
private fun SupportChatContent(
    uiState: SupportChatUiState,
    onBackClick: () -> Unit,
    onStart: () -> Unit,
    onRetryLoad: () -> Unit,
    conversation: @Composable (LazyListState) -> Unit
) {
    val listState = rememberLazyListState()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HawkBackTopBar(title = "Support", onBackClick = onBackClick) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            when (uiState.phase) {
                SupportChatPhase.LOADING -> CenteredLoading()
                SupportChatPhase.LOAD_FAILED -> CenteredMessage(
                    title = "Couldn't open Support",
                    message = uiState.loadError,
                    actionLabel = if (uiState.sessionExpired) null else "Retry",
                    onAction = onRetryLoad
                )
                SupportChatPhase.NO_CONVERSATION -> SupportIntroState(
                    isStarting = uiState.isStarting,
                    error = uiState.startError,
                    onStart = onStart
                )
                SupportChatPhase.READY -> conversation(listState)
            }
        }
    }
}

/**
 * The conversation area shared by the member and admin screens: messages,
 * banners and the composer (or the closed / view-only strip in its place).
 * While the screen is visible it polls for new messages and reports which
 * messages were actually shown, so read markers never move for unseen ones.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun SupportConversationPane(
    viewModel: SupportChatViewModel,
    uiState: SupportChatUiState,
    listState: LazyListState,
    emptyTitle: String,
    emptyMessage: String,
    header: (@Composable () -> Unit)? = null,
    closedAction: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    ResizeForKeyboard()
    val snackbarHostState = remember { SnackbarHostState() }
    var viewerImageId by rememberSaveable { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.onAttachmentPicked(uri.toString(), SupportAttachmentKind.IMAGE)
    }
    val pickPdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onAttachmentPicked(uri.toString(), SupportAttachmentKind.FILE)
    }

    // Only while visible: poll for new messages and report what is on screen.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            coroutineScope {
                launch { viewModel.pollWhileVisible() }
                launch {
                    snapshotFlow {
                        // reverseLayout: the lowest visible index is the newest message on screen.
                        listState.layoutInfo.visibleItemsInfo
                            .filter { messageIdFromKey(it.key) != null }
                            .minByOrNull { it.index }
                            ?.let { messageIdFromKey(it.key) }
                    }
                        .filterNotNull()
                        .distinctUntilChanged()
                        .debounce(SEEN_DEBOUNCE_MS)
                        .collectLatest { viewModel.onMessagesSeen(it) }
                }
            }
        }
    }

    // Older pages when the user reaches the top (the end of the reversed list).
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }
            .distinctUntilChanged()
            .filter { it }
            .collectLatest { viewModel.loadOlder() }
    }

    // Keep the newest message in view when it arrives, if the user was already at the bottom or sent it.
    val newest = uiState.messages.lastOrNull()
    LaunchedEffect(newest?.id) {
        if (newest != null && (newest.isOutgoing || listState.firstVisibleItemIndex <= 2)) {
            listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is SupportChatEvent.OpenFile -> if (!openFile(context, event.file, event.contentType)) viewModel.onNoViewerApp()
            }
        }
    }

    LaunchedEffect(uiState.notice) {
        val notice = uiState.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice)
        viewModel.dismissNotice()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            header?.invoke()
            val inlineError = uiState.messagesError
            if (inlineError != null) {
                SupportInlineError(
                    message = inlineError,
                    onRetry = if (uiState.sessionExpired) null else ({
                        if (uiState.messages.isEmpty()) viewModel.loadLatest() else viewModel.refreshNow()
                    }),
                    modifier = Modifier.padding(horizontal = ScreenHorizontalPadding, vertical = 8.dp)
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                when {
                    uiState.messages.isEmpty() && uiState.isLoadingMessages -> CenteredLoading()
                    uiState.messages.isEmpty() && inlineError == null -> SupportChatEmptyState(emptyTitle, emptyMessage)
                    else -> SupportMessageList(
                        uiState = uiState,
                        listState = listState,
                        actions = SupportChatListActions(
                            onImageVisible = viewModel::requestImage,
                            onImageClick = { id -> viewerImageId = id },
                            onFileClick = viewModel::openFile,
                            onRetryImage = viewModel::requestImage
                        )
                    )
                }
            }
            when {
                uiState.readOnly -> ReadOnlyStrip(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = ScreenHorizontalPadding, vertical = 12.dp)
                )
                uiState.isClosed -> ClosedConversationStrip(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = ScreenHorizontalPadding, vertical = 12.dp),
                    action = closedAction
                )
                else -> SupportComposer(
                    uiState = uiState,
                    actions = SupportComposerActions(
                        onDraftChange = viewModel::onDraftChange,
                        onPickImage = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onPickPdf = { pickPdf.launch(arrayOf("application/pdf")) },
                        onRemoveAttachment = viewModel::removePendingAttachment,
                        onSend = viewModel::send
                    )
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp)
        )
    }

    val viewerBitmap = viewerImageId?.let { (uiState.images[it] as? SupportImageState.Loaded)?.bitmap }
    if (viewerBitmap != null) {
        SupportImageViewer(bitmap = viewerBitmap, onDismiss = { viewerImageId = null })
    }
}

/**
 * While the chat is on screen the system must not move the window for the
 * keyboard; the composer's single imePadding() lifts it instead, and the
 * message list gets the remaining height.
 *
 * On Android 11+ the visible area Android uses to "bring the focused field
 * into view" excludes the keyboard in every mode except adjustNothing - so
 * under pan AND resize the whole window was panned up (top bar pushed off
 * screen) on top of imePadding(), leaving a keyboard-high empty band.
 * adjustNothing stops that pan and resize while keyboard insets still reach
 * Compose. Android 10 and older only report keyboard insets under
 * adjustResize, so they keep it. The previous mode is restored on exit, so
 * no other screen changes.
 */
@Composable
private fun ResizeForKeyboard() {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val previous = window.attributes.softInputMode
        val adjust = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        } else {
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        window.setSoftInputMode((previous and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or adjust)
        onDispose { window.setSoftInputMode(previous) }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Opens a downloaded attachment (in the app's private cache) with another
 * app through the FileProvider, granting read access to that one file only.
 */
private fun openFile(context: Context, file: File, contentType: String): Boolean {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, contentType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

// Previews use placeholder values only; the real screen shows nothing but backend data.

@Preview(showBackground = true)
@Composable
private fun SupportIntroPreview() {
    Hawk_WalletTheme {
        SupportChatContent(
            uiState = SupportChatUiState(phase = SupportChatPhase.NO_CONVERSATION),
            onBackClick = {},
            onStart = {},
            onRetryLoad = {}
        ) {}
    }
}
