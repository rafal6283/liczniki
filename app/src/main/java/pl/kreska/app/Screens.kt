package pl.kreska.app

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate

/* ---------- wspólne elementy ---------- */

@Composable
fun Swatch(color: Color, size: Int = 10) {
    Box(Modifier.size(size.dp).clip(RoundedCornerShape(3.dp)).background(color))
}

@Composable
fun Eyebrow(text: String) {
    Text(
        text.uppercase(Dates.PL),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
    )
}

@Composable
fun SectionTitle(title: String, sub: String? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AppCard(container: Color = MaterialTheme.colorScheme.surface, border: Color = MaterialTheme.colorScheme.outlineVariant, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, border),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun EmptyCard(eyebrow: String, title: String, body: String, actions: @Composable ColumnScope.() -> Unit) {
    AppCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Eyebrow(eyebrow)
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            actions()
        }
    }
}

@Composable
fun QuickRow(names: List<String>, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        names.forEach { n -> OutlinedButton(onClick = { onPick(n) }) { Text("+ $n") } }
    }
}

@Composable
fun MoreButton(desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
        Icon(Icons.Filled.MoreVert, contentDescription = desc, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/* ---------- Liczniki ---------- */

@Composable
fun CountersScreen(v: Long) {
    val counters = remember(v) { Repo.counters() }
    var editing by remember { mutableStateOf<Counter?>(null) }
    var creating by remember { mutableStateOf(false) }
    var entriesFor by remember { mutableStateOf<Counter?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Dziś", Dates.longDay(LocalDate.now())) }
        if (counters.isEmpty()) {
            item {
                EmptyCard(
                    "Brak liczników",
                    "Co chcesz liczyć?",
                    "Każde stuknięcie w duży przycisk dodaje kreskę do dzisiejszego wyniku. Licznik pojawi się też w powiadomieniach z przyciskami + i −.",
                ) {
                    QuickRow(listOf("Szklanki wody", "Pompki", "Kawa", "Przerwy")) { name ->
                        Repo.saveCounter(Counter(0, name, 1, null, Palette.nextFree(counters.map { it.color }), true))
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { creating = true }) { Text("Nowy licznik") }
                }
            }
        }
        items(counters, key = { it.id }) { c -> CounterCard(c, v, onEdit = { editing = c }, onEntries = { entriesFor = c }) }
        if (counters.isNotEmpty()) {
            item {
                OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Nowy licznik") }
            }
        }
    }

    if (creating) CounterDialog(null, Palette.nextFree(counters.map { it.color })) { creating = false }
    editing?.let { c -> CounterDialog(c, c.color) { editing = null } }
    entriesFor?.let { c -> EntriesDialog(c, LocalDate.now()) { entriesFor = null } }
}

@Composable
fun CounterCard(c: Counter, v: Long, onEdit: () -> Unit, onEntries: () -> Unit) {
    val total = remember(v, c.id) { Repo.todayTotal(c.id) }
    val last = remember(v, c.id) { Repo.lastTap(c.id) }
    val col = Palette.color(c.color)
    val haptic = LocalHapticFeedback.current
    val goal = c.goal

    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(col)
                    Spacer(Modifier.width(8.dp))
                    Text(c.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    MoreButton("Ustawienia licznika ${c.name}", onEdit)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Num.fmt(total), fontSize = 44.sp, lineHeight = 46.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    if (goal != null) {
                        Text(" / ${Num.fmt(goal)}", fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                Text(
                    (if (last != null) "ostatnio ${Dates.hm(last)}" else "jeszcze nic dziś") + if (c.step != 1) " · krok ${Num.fmt(c.step)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onEntries,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) { Text("Wpisy · dodaj / edytuj", style = MaterialTheme.typography.labelMedium) }
            }
            OutlinedButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    Repo.decrement(c.id)
                },
                enabled = total > 0,
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(48.dp),
            ) { Text("−", fontSize = 24.sp) }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    Repo.increment(c.id)
                },
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = col, contentColor = Palette.onColor()),
                modifier = Modifier.size(84.dp),
            ) { Text("+${Num.fmt(c.step)}", fontSize = 22.sp, fontFamily = FontFamily.Monospace) }
        }
        if (goal != null) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { (total.toFloat() / goal).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = col,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        if (c.step == 1 && total in 1..40) {
            Spacer(Modifier.height(10.dp))
            Tally(total, col)
        }
    }
}

/* ---------- Zdarzenia ---------- */

data class SessionEdit(val session: Session?, val trackerId: Long)

@Composable
fun EventsScreen(v: Long) {
    val trackers = remember(v) { Repo.trackers() }
    val running = remember(v) { Repo.running().associateBy { it.trackerId } }
    val recent = remember(v) { Repo.recentSessions(12) }
    val pauses = remember(v) { Repo.pausesFor((running.values.map { it.id } + recent.map { it.id }).toSet()) }
    val byId = trackers.associateBy { it.id }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val hasRunning = running.isNotEmpty()
    LaunchedEffect(hasRunning) {
        while (hasRunning) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    var editingTracker by remember { mutableStateOf<Tracker?>(null) }
    var creating by remember { mutableStateOf(false) }
    var sessionEdit by remember { mutableStateOf<SessionEdit?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Zdarzenia", "start, pauza, stop") }
        if (trackers.isEmpty()) {
            item {
                EmptyCard(
                    "Brak typów zdarzeń",
                    "Co chcesz mierzyć w czasie?",
                    "Naciśnij Start, gdy coś się zaczyna, i Stop, gdy się kończy — także z powiadomienia. W trakcie możesz zrobić pauzę. Zdarzenia z przeszłości dopiszesz ręcznie.",
                ) {
                    QuickRow(listOf("Praca", "Sen", "Ból głowy", "Trening")) { name ->
                        Repo.saveTracker(Tracker(0, name, Palette.nextFree(trackers.map { it.color }), true))
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { creating = true }) { Text("Nowy typ zdarzenia") }
                }
            }
        }
        items(trackers, key = { it.id }) { t ->
            val s = running[t.id]
            if (s != null) RunningCard(t, s, pauses[s.id] ?: emptyList(), now, onEdit = { sessionEdit = SessionEdit(s, t.id) })
            else TrackerCard(t, v, onEdit = { editingTracker = t }, onAdd = { sessionEdit = SessionEdit(null, t.id) })
        }
        if (trackers.isNotEmpty()) {
            item { OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Nowy typ zdarzenia") } }
        }
        if (recent.isNotEmpty()) {
            item {
                Spacer(Modifier.height(10.dp))
                Text("Ostatnie wpisy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(recent, key = { "s${it.id}" }) { s ->
                val ps = pauses[s.id] ?: emptyList()
                SessionRow(s, byId[s.trackerId], now, Stats.activeMs(s, ps, now), ps.size) { sessionEdit = SessionEdit(s, s.trackerId) }
            }
        }
    }

    if (creating) TrackerDialog(null, Palette.nextFree(trackers.map { it.color })) { creating = false }
    editingTracker?.let { t -> TrackerDialog(t, t.color) { editingTracker = null } }
    sessionEdit?.let { e -> SessionDialog(e.session, e.trackerId) { sessionEdit = null } }
}

@Composable
fun RunningCard(t: Tracker, s: Session, pauses: List<Pause>, now: Long, onEdit: () -> Unit) {
    val live = Palette.live()
    val open = pauses.firstOrNull { it.end == null }
    val paused = open != null
    val active = Stats.activeMs(s, pauses, now)
    val pausedMs = now - s.start - active
    val accent = if (paused) MaterialTheme.colorScheme.onSurfaceVariant else live
    AppCard(
        container = if (paused) MaterialTheme.colorScheme.surfaceVariant else Palette.liveSoft(),
        border = accent,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(Palette.color(t.color))
            Spacer(Modifier.width(8.dp))
            Text(t.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(if (paused) "PAUZA" else "TRWA", style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(Dates.clock(active), fontSize = 34.sp, fontFamily = FontFamily.Monospace, color = accent, fontWeight = FontWeight.Medium)
        val startedToday = Dates.keyOf(s.start) == Dates.todayKey()
        val pauseInfo = when {
            open != null -> " · wstrzymane o ${Dates.hm(open.start)}"
            pauses.isNotEmpty() -> " · pauzy: ${pauses.size} (${Dates.dur(pausedMs)})"
            else -> ""
        }
        Text(
            "od ${Dates.hm(s.start)}" + (if (startedToday) "" else ", ${Dates.dayOf(s.start)}") + pauseInfo,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Edytuj") }
            OutlinedButton(
                onClick = { if (paused) Repo.resumeSession(s.id) else Repo.pauseSession(s.id) },
                modifier = Modifier.weight(1f),
            ) { Text(if (paused) "Wznów" else "Pauza", fontWeight = FontWeight.SemiBold) }
            Button(
                onClick = { Repo.stopSession(s.id) },
                colors = ButtonDefaults.buttonColors(containerColor = live, contentColor = Palette.onColor()),
                modifier = Modifier.weight(1f),
            ) { Text("Stop", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun TrackerCard(t: Tracker, v: Long, onEdit: () -> Unit, onAdd: () -> Unit) {
    val last = remember(v, t.id) { Repo.lastFinished(t.id) }
    val lastActive = remember(v, t.id) { last?.let { Repo.activeMs(it) } }
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(Palette.color(t.color))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(t.name, fontWeight = FontWeight.SemiBold)
                Text(
                    if (last != null && lastActive != null) "ostatnio ${Dates.dayOf(last.start)}, ${Dates.dur(lastActive)}" else "brak wpisów",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MoreButton("Ustawienia: ${t.name}", onEdit)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAdd) { Text("Dopisz") }
            Button(onClick = { Repo.startSession(t.id) }, modifier = Modifier.weight(1f)) { Text("Start", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
fun SessionRow(s: Session, t: Tracker?, now: Long, activeMs: Long?, pauseCount: Int, onEdit: () -> Unit) {
    val end = s.end
    val active = activeMs ?: ((end ?: now) - s.start)
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(if (t != null) Palette.color(t.color) else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t?.name ?: "(usunięty)", fontWeight = FontWeight.SemiBold)
                Text(
                    "${Dates.dayOf(s.start)}, ${Dates.hm(s.start)}" + (if (end != null) "–${Dates.hm(end)}" else "") +
                        (if (pauseCount > 0) " · pauzy: $pauseCount" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.note.isNotBlank()) Text(s.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (end != null) Dates.dur(active) else "trwa ${Dates.dur(active)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = if (end != null) MaterialTheme.colorScheme.onSurface else Palette.live(),
            )
            Icon(Icons.Filled.Edit, contentDescription = "Edytuj wpis", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(16.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/* ---------- Raporty ---------- */

@Composable
fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 19.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable
fun StatsGrid(items: List<Pair<String, String>>) {
    Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (v, l) -> Stat(v, l, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun ReportsScreen(v: Long) {
    val ctx = LocalContext.current
    var range by rememberSaveable { mutableIntStateOf(30) }
    var detailCounter by rememberSaveable { mutableLongStateOf(-1L) }
    var detailTracker by rememberSaveable { mutableLongStateOf(-1L) }
    val to = LocalDate.now()
    val from = to.minusDays(range - 1L)
    val days = remember(range, v) { (0 until range).map { from.plusDays(it.toLong()) } }
    val counters = remember(v) { Repo.counters() }
    val trackers = remember(v) { Repo.trackers() }
    var dayValueFor by remember { mutableStateOf<Counter?>(null) }
    var sessionEdit by remember { mutableStateOf<SessionEdit?>(null) }
    var entriesDay by remember { mutableStateOf<Pair<Counter, LocalDate>?>(null) }
    var detailCompare by rememberSaveable { mutableStateOf(false) }
    var compareSettings by remember { mutableStateOf(false) }
    var compareCfg by remember { mutableIntStateOf(0) }

    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Toast.makeText(ctx, Exchange.importUri(ctx, uri), Toast.LENGTH_LONG).show()
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(Repo.csv(from).toByteArray(Charsets.UTF_8)) }
                Toast.makeText(ctx, "Zapisano plik CSV", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Nie udało się zapisać pliku", Toast.LENGTH_LONG).show()
            }
        }
    }

    // eksport wpisów jednego licznika z godzinami (JSON) – np. FF do Dziennika talerza
    var jsonPick by remember { mutableStateOf(false) }
    var jsonCounter by remember { mutableStateOf<Counter?>(null) }
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val c = jsonCounter
        if (uri != null && c != null) {
            try {
                val r = KreskaExport.build(c)
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(r.json.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(ctx, "Zapisano: ${r.events} wpisów z godziną, ${r.days} dni", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Nie udało się zapisać pliku", Toast.LENGTH_LONG).show()
            }
        }
    }
    fun startJson(c: Counter) {
        jsonCounter = c
        val safe = c.name.trim().replace(Regex("[^A-Za-z0-9ĄĆĘŁŃÓŚŹŻąćęłńóśźż_-]+"), "-").ifEmpty { "licznik" }
        exportJson.launch("kreska-$safe-$to.json")
    }

    val dc = if (detailCounter >= 0) counters.firstOrNull { it.id == detailCounter } else null
    val dt = if (detailTracker >= 0) trackers.firstOrNull { it.id == detailTracker } else null

    when {
        detailCompare -> {
            BackHandler { detailCompare = false }
            CompareDetail(v, days, range, { range = it }, compareCfg, onBack = { detailCompare = false })
        }
        dc != null -> {
            BackHandler { detailCounter = -1L }
            CounterDetail(
                dc, days, range, { range = it }, v,
                onBack = { detailCounter = -1L },
                onSetDay = { dayValueFor = dc },
                onDay = { d -> entriesDay = dc to d },
            )
        }
        dt != null -> {
            BackHandler { detailTracker = -1L }
            TrackerDetail(
                dt, days, range, { range = it }, v,
                onBack = { detailTracker = -1L },
                onEdit = { s -> sessionEdit = SessionEdit(s, dt.id) },
                onAdd = { sessionEdit = SessionEdit(null, dt.id) },
            )
        }
        else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionTitle("Raporty")
                RangeChips(range) { range = it }
            }
            if (counters.isEmpty() && trackers.isEmpty()) {
                item {
                    EmptyCard("Brak danych", "Tu pojawią się wykresy", "Dodaj licznik albo typ zdarzenia, a raporty zbudują się same.") {}
                }
            }
            items(counters, key = { "c${it.id}" }) { c -> CounterReport(c, days, v) { detailCounter = c.id } }
            items(trackers, key = { "t${it.id}" }) { t -> TrackerReport(t, days, v) { detailTracker = t.id } }
            item(key = "compare") {
                CompareCard(
                    v, from, to, compareCfg,
                    onImport = { importFile.launch(arrayOf("*/*")) },
                    onSettings = { compareSettings = true },
                    onOpen = { detailCompare = true },
                )
            }
            if (counters.isNotEmpty() || trackers.isNotEmpty()) {
                item {
                    OutlinedButton(onClick = { export.launch("kreska-$to.csv") }, modifier = Modifier.fillMaxWidth()) {
                        Text("Eksportuj CSV (${if (range == 365) "rok" else "$range dni"})")
                    }
                }
            }
            if (counters.isNotEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { if (counters.size == 1) startJson(counters.first()) else jsonPick = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Eksportuj wpisy z godzinami (JSON)")
                    }
                    Text(
                        "Cała historia jednego licznika – każde kliknięcie z godziną. Np. FF do Dziennika talerza.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    dayValueFor?.let { c -> DayValueDialog(c) { dayValueFor = null } }
    sessionEdit?.let { e -> SessionDialog(e.session, e.trackerId) { sessionEdit = null } }
    entriesDay?.let { (c, d) -> EntriesDialog(c, d) { entriesDay = null } }
    if (jsonPick) {
        val preferred = ComparePrefs.counterId(ctx, counters)
        AlertDialog(
            onDismissRequest = { jsonPick = false },
            title = { Text("Który licznik?") },
            text = {
                Column {
                    for (c in counters) {
                        TextButton(onClick = { jsonPick = false; startJson(c) }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                c.name,
                                fontWeight = if (c.id == preferred) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { jsonPick = false }) { Text("Anuluj") } },
        )
    }
    if (compareSettings) CompareSettingsDialog(onDismiss = { compareSettings = false }, onSaved = { compareCfg++ })
}

@Composable
fun CounterReport(c: Counter, days: List<LocalDate>, v: Long, onOpen: () -> Unit) {
    val st = remember(v, days, c.id) { Stats.counter(c, days) }
    val dd = st.days
    val n = dd.size
    val col = Palette.color(c.color)
    val tr = st.trend
    var peak = -1
    for (i in 0 until 24) if (st.hours[i] > 0f && (peak < 0 || st.hours[i] > st.hours[peak])) peak = i

    AppCard {
        Eyebrow("Licznik")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(col)
            Spacer(Modifier.width(8.dp))
            Text(c.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        StatsGrid(
            listOf(
                Num.fmt(st.sum) to "suma w okresie",
                Num.fmt(st.avgPerDay) to ("średnio / dzień" + if (tr != null) " ($tr)" else ""),
                (if (st.bestIdx >= 0) Num.fmt(st.values[st.bestIdx]) else "–") to
                    (if (st.bestIdx >= 0) "rekord, ${Dates.day(dd[st.bestIdx])}" else "rekord"),
                (if (st.goalDays != null) "${st.goalDays}/$n" else "${st.activeDays}/$n") to
                    (if (st.goalDays != null) "dni z celem" else "dni z wpisem"),
                (if (peak >= 0) "$peak:00" else "–") to "najczęstsza godzina",
                "${st.streakCurrent} d" to "obecna seria",
            )
        )
        BarChart(dd, st.values, col) { Num.fmt(it) }
        SinceNote(st.since, st.trimmed)
        TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Pełne statystyki →") }
    }
}

@Composable
fun TrackerReport(t: Tracker, days: List<LocalDate>, v: Long, onOpen: () -> Unit) {
    val st = remember(v, days, t.id) { Stats.tracker(t, days) }
    val col = Palette.color(t.color)
    val trT = st.trendTotal

    AppCard {
        Eyebrow("Zdarzenie")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Swatch(col)
            Spacer(Modifier.width(8.dp))
            Text(t.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        StatsGrid(
            listOf(
                "${st.count}" to "razy",
                (if (st.totalMs > 0) Dates.dur(st.totalMs) else "–") to "łącznie w okresie",
                Dates.dur(st.avgPerDayMs) to ("czasu dziennie" + if (trT != null) " ($trT)" else ""),
                (if (st.count > 0) Dates.dur(st.avgMs) else "–") to "średnio jedno",
                Stats.hhmm(st.avgStartMin) to "średni start",
                "${st.streakCurrent} d" to "obecna seria",
            )
        )
        BarChart(st.days, st.dailyMinutes, col) { Stats.minutesLabel(it) }
        SinceNote(st.since, st.trimmed)
        TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Pełne statystyki →") }
    }
}

@Composable
fun SinceNote(since: LocalDate?, trimmed: Boolean) {
    if (trimmed && since != null) {
        Text(
            "Dane od ${Dates.date(since)} — średnie liczone od tego dnia.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
