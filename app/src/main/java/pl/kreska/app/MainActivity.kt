package pl.kreska.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TAB = "pl.kreska.app.TAB"
    }

    private val tab = mutableIntStateOf(0)
    private val showResume = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Repo.init(this)
        if (savedInstanceState == null) {
            tab.intValue = intent.getIntExtra(EXTRA_TAB, 0)
            handleImport(intent)
            // Powrót po zamknięciu aplikacji lub restarcie telefonu, gdy coś wciąż trwa.
            if (Prefs.consumeResumeNeeded(this, Repo.running().isNotEmpty())) {
                showResume.value = true
                tab.intValue = 1
            }
        }
        Prefs.touch(this)
        setContent {
            KreskaTheme {
                AppRoot(tab)
                if (showResume.value) ResumeDialog(onDismiss = { showResume.value = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra(EXTRA_TAB)) tab.intValue = intent.getIntExtra(EXTRA_TAB, tab.intValue)
        handleImport(intent)
    }

    /** Plik udostępniony z Nocnego licznika lub Polar EKG („Udostępnij → Kreska”). */
    private fun handleImport(i: Intent) {
        val uri: Uri? = when (i.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> i.data
            else -> null
        }
        if (uri == null) return
        Toast.makeText(this, Exchange.importUri(this, uri), Toast.LENGTH_LONG).show()
        tab.intValue = 2
        // Ten sam intent nie zaimportuje się drugi raz (np. po obrocie ekranu).
        i.action = null
    }

    override fun onStop() {
        super.onStop()
        Prefs.touch(this)
    }

    override fun onResume() {
        super.onResume()
        pl.rafal.koofr.Koofr.autoRunAsync()
        // Dane mogły się zmienić z poziomu powiadomień albo minęła północ.
        Notifier.refresh(this)
        Repo.bump()
    }
}

private val TAB_LABELS = listOf("Liczniki", "Zdarzenia", "Raporty")
private val TAB_ICONS = listOf(R.drawable.ic_nav_tally, R.drawable.ic_nav_timer, R.drawable.ic_nav_chart)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(tabState: MutableIntState) {
    var tab by tabState
    var showSettings by remember { mutableStateOf(false) }
    var showKoofr by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        Notifier.refresh(ctx)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val v by Repo.version.collectAsState()
    val anyRunning = remember(v) { Repo.running().isNotEmpty() }

    if (showKoofr) {
        androidx.activity.compose.BackHandler { showKoofr = false }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.statusBarsPadding()) { pl.rafal.koofr.KoofrScreen(onBack = { showKoofr = false }) }
        }
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Kreska", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ustawienia")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                TAB_LABELS.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            BadgedBox(badge = { if (i == 1 && anyRunning) Badge() }) {
                                Icon(painterResource(TAB_ICONS[i]), contentDescription = null)
                            }
                        },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> CountersScreen(v)
                1 -> EventsScreen(v)
                else -> ReportsScreen(v)
            }
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false }, onKoofr = { showSettings = false; showKoofr = true })
}
