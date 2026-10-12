package app.gemma.android

import android.Manifest
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

enum class Tab(val label: String) { CHAT("Chat"), VOICE("Voice"), MEMORY("Memory"), TOOLS("Tools") }

class MainActivity : ComponentActivity() {
    private val viewModel: GemmaViewModel by viewModels()

    /** Where a notification, a share from another app or the home-screen shortcut asked to go. */
    private val request = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        if (savedInstanceState == null) handle(intent)
        setContent {
            GemmaTheme {
                GemmaApp(viewModel, request.value) { request.value = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        when {
            intent.action == ACTION_LIVE -> request.value = "live"
            viewModel.takeShared(intent) -> request.value = "chat"
            intent.hasExtra(Notices.EXTRA_CONVERSATION) -> {
                val id = intent.getLongExtra(Notices.EXTRA_CONVERSATION, -1)
                if (id > 0) viewModel.openChat(id, "")
                request.value = "chat"
            }
        }
    }

    companion object {
        const val ACTION_LIVE = "app.gemma.android.LIVE"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GemmaApp(viewModel: GemmaViewModel, request: String?, onRequestHandled: () -> Unit) {
    var showSettings by rememberSaveable { mutableStateOf(!viewModel.isConfigured) }
    var tab by rememberSaveable { mutableStateOf(Tab.CHAT) }
    var startLive by rememberSaveable { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val chat by viewModel.chat.collectAsStateWithLifecycle()

    // Coming back to the app (screen on, switching back, network returned): check in with Vision again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onBackground() }

    LaunchedEffect(request) {
        when (request) {
            "live" -> { tab = Tab.VOICE; startLive = true }
            "chat" -> tab = Tab.CHAT
        }
        if (request != null) onRequestHandled()
    }

    // Ask once, after setup, whether Gemma may show notifications.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(showSettings) {
        if (!showSettings && viewModel.isConfigured && Build.VERSION.SDK_INT >= 33 && !viewModel.askedForNotifications) {
            viewModel.askedForNotifications = true
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (showSettings) {
        BackHandler(enabled = viewModel.isConfigured) { showSettings = false }
        SettingsScreen(viewModel, onDone = { showSettings = false })
        return
    }

    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp),
                drawerContainerColor = Space.Surface,
                drawerContentColor = Space.Text,
            ) {
                Sidebar(viewModel, onPicked = {
                    tab = Tab.CHAT
                    scope.launch { drawer.close() }
                })
            }
        },
    ) {
        Scaffold(
            containerColor = Space.Background,
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Space.Background, titleContentColor = Space.Text,
                        navigationIconContentColor = Space.Text, actionIconContentColor = Space.Text,
                    ),
                    title = {
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Text("GEMMA", fontSize = 20.sp, letterSpacing = 5.sp, fontWeight = FontWeight.Light)
                            // Words, not just a coloured dot, so the state is clear to everyone.
                            Text("● " + chat.statusLine, style = MaterialTheme.typography.labelSmall, color = Space.Muted)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.refreshChats()
                            scope.launch { drawer.open() }
                        }) { Icon(Icons.Filled.Menu, contentDescription = "Chats") }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                    },
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Space.Surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { t ->
                        val symbol: ImageVector = when (t) {
                            Tab.CHAT -> Glyph.Chat
                            Tab.VOICE -> Glyph.Mic
                            Tab.MEMORY -> Glyph.Memory
                            Tab.TOOLS -> Icons.Filled.Build
                        }
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(symbol, contentDescription = null) },
                            label = { Text(t.label) },
                            modifier = Modifier.testTag("tab-" + t.label),
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Space.Blue, selectedTextColor = Space.Blue,
                                unselectedIconColor = Space.Muted, unselectedTextColor = Space.Muted,
                                indicatorColor = Color(0x334C8DFF),
                            ),
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when (tab) {
                    Tab.CHAT -> ChatScreen(viewModel)
                    Tab.VOICE -> VoiceScreen(
                        viewModel, autoStart = startLive, onAutoStarted = { startLive = false },
                        onVoiceSettings = { showSettings = true },
                    )
                    Tab.MEMORY -> MemoryScreen(viewModel)
                    Tab.TOOLS -> ToolsScreen(viewModel, onGoToChat = { tab = Tab.CHAT })
                }
            }
        }
    }
}
