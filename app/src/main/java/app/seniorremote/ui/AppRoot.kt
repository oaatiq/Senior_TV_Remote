package app.seniorremote.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.seniorremote.AppContainer
import app.seniorremote.R
import app.seniorremote.domain.CommandFeedback
import app.seniorremote.text.TextEntryController
import app.seniorremote.ui.admin.AdminScreen
import app.seniorremote.ui.admin.AdminViewModel
import app.seniorremote.ui.admin.PinGateDialog
import app.seniorremote.ui.admin.TvDetailsScreen
import app.seniorremote.ui.common.LocalTvImages
import app.seniorremote.ui.remote.ChooseTvScreen
import app.seniorremote.ui.remote.RemoteScreen
import app.seniorremote.ui.remote.RemoteViewModel
import app.seniorremote.ui.remote.TextEntryScreen
import app.seniorremote.ui.setup.SetupEntry
import app.seniorremote.ui.setup.SetupFlow
import app.seniorremote.ui.setup.SetupViewModel
import app.seniorremote.ui.setup.WelcomeScreen
import kotlinx.coroutines.launch

private enum class Mode { AUTO, FIRST_TV, ADD_TV, RETEST, REPAIR, ADMIN, TV_DETAILS, CHOOSER, TYPE_TEXT }

/**
 * Chooses the screen. The remote for the selected TV opens directly; setup appears only when no
 * TV is saved or the administrator asks for it, and the chooser opens only on request or when
 * several TVs remain and none is selected.
 */
@Composable
fun AppRoot(container: AppContainer, onRemoteFirstFrame: () -> Unit) {
    val library by container.repository.library.collectAsStateWithLifecycle()
    val connection by container.connection.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(Mode.AUTO) }
    var subjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyTextEntry by rememberSaveable { mutableStateOf(false) }
    var textRun by rememberSaveable { mutableIntStateOf(0) }
    var setupRun by rememberSaveable { mutableIntStateOf(0) }
    var pinGate by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val remote: RemoteViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                RemoteViewModel(container.connection, container.dispatcher, container.repository, container.settings, container.elapsedClock)
            }
        },
    )

    fun startSetup(next: Mode) {
        setupRun++
        mode = next
    }

    CompositionLocalProvider(LocalTvImages provides container.images) {
        when {
            mode == Mode.FIRST_TV || mode == Mode.ADD_TV || mode == Mode.RETEST || mode == Mode.REPAIR -> {
                val returnsToDetails = mode == Mode.RETEST || mode == Mode.REPAIR
                val setup = remember(setupRun) {
                    SetupViewModel(
                        entry = when (mode) {
                            Mode.RETEST -> SetupEntry.Retest(requireNotNull(subjectId), onlyTextEntry)
                            Mode.REPAIR -> SetupEntry.Repair(requireNotNull(subjectId))
                            Mode.ADD_TV -> SetupEntry.AddTv
                            else -> SetupEntry.FirstTv
                        },
                        adminPin = container.adminPin,
                        discovery = container.discovery,
                        pairing = container.pairing,
                        repository = container.repository,
                        credentials = container.credentials,
                        tvManager = container.tvManager,
                        connection = container.connection,
                        dispatcher = container.dispatcher,
                        network = container.network,
                        diagnostics = container.diagnostics,
                        clock = container.elapsedClock,
                    )
                }
                DisposableEffect(setup) { onDispose { setup.dispose() } }
                SetupFlow(
                    viewModel = setup,
                    finishLabel = if (returnsToDetails) "Back to settings" else stringResource(R.string.done_button),
                    onExit = {
                        mode = when (mode) {
                            Mode.ADD_TV -> Mode.ADMIN
                            Mode.RETEST, Mode.REPAIR -> Mode.TV_DETAILS
                            else -> Mode.AUTO
                        }
                    },
                    onFinished = { mode = if (returnsToDetails) Mode.TV_DETAILS else Mode.AUTO },
                )
            }

            library.tvs.isEmpty() -> WelcomeScreen(onSetUp = { startSetup(Mode.FIRST_TV) })

            mode == Mode.ADMIN || mode == Mode.TV_DETAILS -> {
                val admin: AdminViewModel = viewModel(factory = viewModelFactory { initializer { AdminViewModel(container) } })
                val id = subjectId
                if (mode == Mode.TV_DETAILS && id != null && library.find(id) != null) {
                    TvDetailsScreen(
                        viewModel = admin,
                        tvId = id,
                        onBack = { mode = Mode.ADMIN },
                        onRetest = {
                            // Testing needs a session, and there is only ever one: with this TV.
                            scope.launch {
                                container.tvManager.select(id)
                                onlyTextEntry = false
                                startSetup(Mode.RETEST)
                            }
                        },
                        onTestText = {
                            scope.launch {
                                container.tvManager.select(id)
                                onlyTextEntry = true
                                startSetup(Mode.RETEST)
                            }
                        },
                        onRepair = { startSetup(Mode.REPAIR) },
                    )
                } else {
                    AdminScreen(
                        viewModel = admin,
                        onClose = { mode = Mode.AUTO },
                        onOpenTv = {
                            subjectId = it
                            mode = Mode.TV_DETAILS
                        },
                        onAddTv = { startSetup(Mode.ADD_TV) },
                    )
                }
            }

            library.selected == null || mode == Mode.CHOOSER -> ChooseTvScreen(
                library = library,
                connection = connection,
                canGoBack = library.selected != null,
                onSelect = { id ->
                    scope.launch {
                        container.tvManager.select(id)
                        mode = Mode.AUTO
                    }
                },
                onBack = { mode = Mode.AUTO },
            )

            mode == Mode.TYPE_TEXT && subjectId != null && library.find(requireNotNull(subjectId)) != null -> {
                val tv = requireNotNull(library.find(requireNotNull(subjectId)))
                val entry = remember(textRun) {
                    TextEntryController(
                        tvId = tv.id,
                        repository = container.repository,
                        connection = container.connection,
                        diagnostics = container.diagnostics,
                        onSent = {
                            remote.show(CommandFeedback.TextSent)
                            mode = Mode.AUTO
                        },
                        onClosed = { mode = Mode.AUTO },
                    )
                }
                DisposableEffect(entry) { onDispose { entry.dispose() } }
                TextEntryScreen(entry, tv, onCancel = { mode = Mode.AUTO })
            }

            else -> {
                RemoteScreen(
                    viewModel = remote,
                    onChooseTv = {
                        container.dispatcher.cancelAll()
                        mode = Mode.CHOOSER
                    },
                    onTypeText = {
                        container.dispatcher.cancelAll()
                        subjectId = library.selectedId
                        textRun++
                        mode = Mode.TYPE_TEXT
                    },
                    onOpenSettings = { pinGate = true },
                    onFirstFrame = onRemoteFirstFrame,
                )
            }
        }
    }

    if (pinGate) {
        PinGateDialog(
            verify = container.adminPin::verify,
            onUnlocked = {
                pinGate = false
                container.dispatcher.cancelAll()
                mode = Mode.ADMIN
            },
            onDismiss = { pinGate = false },
        )
    }
}
