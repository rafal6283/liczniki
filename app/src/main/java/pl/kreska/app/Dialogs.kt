package pl.kreska.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/* ---------- wspólne ---------- */

@Composable
fun ColorPicker(selected: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until Palette.SIZE) {
            val c = Palette.color(i)
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(c)
                    .border(3.dp, if (i == selected) MaterialTheme.colorScheme.onSurface else c, RoundedCornerShape(9.dp))
                    .clickable { onPick(i) }
            )
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun ErrorText(text: String?) {
    if (text != null) Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
fun DeleteButton(label: String, confirmLabel: String, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    TextButton(
        onClick = { if (armed) onConfirm() else armed = true },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
    ) { Text(if (armed) confirmLabel else label, fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal) }
}

fun pickDateTime(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val z = Dates.zdt(initial)
    val dlg = DatePickerDialog(ctx, { _, y, m, d ->
        TimePickerDialog(ctx, { _, h, min ->
            onPicked(LocalDateTime.of(y, m + 1, d, h, min).atZone(Dates.zone).toInstant().toEpochMilli())
        }, z.hour, z.minute, true).show()
    }, z.year, z.monthValue - 1, z.dayOfMonth)
    dlg.datePicker.maxDate = System.currentTimeMillis()
    dlg.show()
}

fun pickDate(ctx: Context, initial: LocalDate, onPicked: (LocalDate) -> Unit) {
    val dlg = DatePickerDialog(ctx, { _, y, m, d -> onPicked(LocalDate.of(y, m + 1, d)) }, initial.year, initial.monthValue - 1, initial.dayOfMonth)
    dlg.datePicker.maxDate = System.currentTimeMillis()
    dlg.show()
}

private fun digits(s: String) = s.filter { it.isDigit() }.take(6)

/* ---------- licznik ---------- */

@Composable
fun CounterDialog(existing: Counter?, defaultColor: Int, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var step by remember { mutableStateOf((existing?.step ?: 1).toString()) }
    var goal by remember { mutableStateOf(existing?.goal?.toString() ?: "") }
    var color by remember { mutableIntStateOf(existing?.color ?: defaultColor) }
    var notify by remember { mutableStateOf(existing?.notify ?: true) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nowy licznik" else "Ustawienia licznika") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Nazwa") }, placeholder = { Text("np. Szklanki wody") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = step, onValueChange = { step = digits(it) },
                        label = { Text("Krok") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = goal, onValueChange = { goal = digits(it) },
                        label = { Text("Cel dzienny") }, placeholder = { Text("brak") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                ColorPicker(color) { color = it }
                SwitchRow("Pokaż w powiadomieniach (+ i −)", notify) { notify = it }
                ErrorText(err)
                if (existing != null) {
                    DeleteButton("Usuń licznik", "Na pewno? Usuń razem z historią") {
                        Repo.deleteCounter(existing.id)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = step.toIntOrNull() ?: 0
                val g = goal.toIntOrNull()
                when {
                    name.isBlank() -> err = "Podaj nazwę licznika."
                    s < 1 -> err = "Krok musi wynosić co najmniej 1."
                    goal.isNotEmpty() && (g == null || g < 1) -> err = "Cel musi być większy od zera."
                    else -> {
                        Repo.saveCounter(Counter(existing?.id ?: 0L, name.trim(), s, g, color, notify))
                        onDismiss()
                    }
                }
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

/* ---------- typ zdarzenia ---------- */

@Composable
fun TrackerDialog(existing: Tracker?, defaultColor: Int, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableIntStateOf(existing?.color ?: defaultColor) }
    var notify by remember { mutableStateOf(existing?.notify ?: true) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nowy typ zdarzenia" else "Ustawienia zdarzenia") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(40) },
                    label = { Text("Nazwa") }, placeholder = { Text("np. Praca, Sen") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                ColorPicker(color) { color = it }
                SwitchRow("Pokaż w powiadomieniach", notify) { notify = it }
                Text(
                    if (notify) "Ciche powiadomienie z przyciskiem Start, a w trakcie ze stoperem i przyciskami Pauza/Stop."
                    else "Zdarzenie nie pojawia się w powiadomieniach, także gdy trwa — zatrzymasz je w aplikacji.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ErrorText(err)
                if (existing != null) {
                    DeleteButton("Usuń", "Na pewno? Usuń razem z historią") {
                        Repo.deleteTracker(existing.id)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) err = "Podaj nazwę." else {
                    Repo.saveTracker(Tracker(existing?.id ?: 0L, name.trim(), color, notify))
                    onDismiss()
                }
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

/* ---------- wpis zdarzenia ---------- */

@Composable
fun DateTimeField(label: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SessionDialog(session: Session?, trackerId: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val createdAt = remember { System.currentTimeMillis() }
    val tracker = remember { Repo.tracker(session?.trackerId ?: trackerId) }
    var start by remember { mutableLongStateOf(session?.start ?: (createdAt - 3_600_000L)) }
    var end by remember { mutableStateOf(if (session == null) createdAt else session.end) }
    var note by remember { mutableStateOf(session?.note ?: "") }
    var err by remember { mutableStateOf<String?>(null) }
    val isRunning = session != null && session.end == null
    var pauseVersion by remember { mutableIntStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text((if (session == null) "Dopisz: " else "Edytuj: ") + (tracker?.name ?: "zdarzenie")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DateTimeField("Początek", Dates.dateTime(start)) { pickDateTime(ctx, start) { start = it } }
                val e = end
                DateTimeField("Koniec", if (e != null) Dates.dateTime(e) else "trwa — dotknij, aby ustawić") {
                    pickDateTime(ctx, e ?: System.currentTimeMillis()) { end = it }
                }
                if (isRunning && e != null) {
                    TextButton(onClick = { end = null }) { Text("Nadal trwa (bez końca)") }
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(200) },
                    label = { Text("Notatka") }, placeholder = { Text("opcjonalnie") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (session != null) {
                    val pauses = remember(pauseVersion) { Repo.pauses(session.id) }
                    if (pauses.isNotEmpty()) {
                        val total = (session.end ?: System.currentTimeMillis()) - session.start -
                            Stats.activeMs(session, pauses, System.currentTimeMillis())
                        Text(
                            "Pauzy: ${pauses.size}, łącznie ${Dates.dur(total)}. Czas zdarzenia nie obejmuje pauz.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        DeleteButton("Usuń pauzy", "Na pewno? Cały czas będzie liczony") {
                            Repo.clearPauses(session.id)
                            pauseVersion++
                        }
                    }
                }
                ErrorText(err)
                if (session != null) {
                    DeleteButton("Usuń wpis", "Na pewno usunąć?") {
                        Repo.deleteSession(session.id)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = end
                when {
                    e != null && e <= start -> err = "Koniec musi być po początku."
                    start > System.currentTimeMillis() + 60_000 -> err = "Początek nie może być w przyszłości."
                    else -> {
                        Repo.saveSession(Session(session?.id ?: 0L, session?.trackerId ?: trackerId, start, e, note.trim()))
                        onDismiss()
                    }
                }
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

/* ---------- korekta dnia ---------- */

@Composable
fun DayValueDialog(c: Counter, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var day by remember { mutableStateOf(LocalDate.now().minusDays(1)) }
    var value by remember { mutableStateOf(Repo.total(c.id, LocalDate.now().minusDays(1).toString()).toString()) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wartość dnia: ${c.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Użyj, gdy zapomnisz coś policzyć albo wynik się pomylił.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DateTimeField("Dzień", Dates.date(day) + " (" + Dates.weekday(day) + ")") {
                    pickDate(ctx, day) { picked ->
                        day = picked
                        value = Repo.total(c.id, picked.toString()).toString()
                    }
                }
                OutlinedTextField(
                    value = value, onValueChange = { value = digits(it) },
                    label = { Text("Wartość") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(err)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = value.toIntOrNull()
                if (v == null) err = "Podaj liczbę." else {
                    Repo.setDayValue(c.id, day, v)
                    onDismiss()
                }
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

/* ---------- ustawienia ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(onDismiss: () -> Unit, onKoofr: () -> Unit = {}) {
    val ctx = LocalContext.current
    var remOn by remember { mutableStateOf(Prefs.reminderOn(ctx)) }
    var remMin by remember { mutableIntStateOf(Prefs.reminderMinutes(ctx)) }
    var overdue by remember { mutableIntStateOf(Prefs.overdueHours(ctx)) }
    var goalNotify by remember { mutableStateOf(Prefs.goalNotify(ctx)) }
    var fields by remember { mutableStateOf(Prefs.notifFields(ctx)) }
    var hideOnLock by remember { mutableStateOf(Prefs.notifHideOnLock(ctx)) }
    val counters = remember { Repo.counters() }
    val trackers = remember { Repo.trackers() }
    var shownC by remember { mutableStateOf(counters.filter { it.notify }.map { it.id }.toSet()) }
    var shownT by remember { mutableStateOf(trackers.filter { it.notify }.map { it.id }.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ustawienia") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Przypomnienie o celach", fontWeight = FontWeight.SemiBold)
                Text(
                    "Wieczorem przypomni, jeśli któryś licznik z celem dziennym nie doszedł do celu.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = remOn, onCheckedChange = { remOn = it })
                    TextButton(enabled = remOn, onClick = {
                        TimePickerDialog(ctx, { _, h, m -> remMin = h * 60 + m }, remMin / 60, remMin % 60, true).show()
                    }) { Text(String.format(Locale.ROOT, "o %02d:%02d", remMin / 60, remMin % 60)) }
                }
                SwitchRow("Powiadom o osiągnięciu celu", goalNotify) { goalNotify = it }
                HorizontalDivider()
                Text("Niezatrzymane zdarzenie", fontWeight = FontWeight.SemiBold)
                Text(
                    "Powiadomienie, gdy zdarzenie trwa dłużej niż:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 1, 2, 4, 8, 12).forEach { h ->
                        FilterChip(selected = overdue == h, onClick = { overdue = h }, label = { Text(if (h == 0) "wył." else "$h h") })
                    }
                }
                HorizontalDivider()
                Text("Ciche powiadomienia w panelu", fontWeight = FontWeight.SemiBold)
                if (counters.isNotEmpty() || trackers.isNotEmpty()) {
                    Text("Widoczne w powiadomieniach", style = MaterialTheme.typography.labelLarge)
                    counters.forEach { c ->
                        SwitchRow("${c.name} (licznik)", c.id in shownC) { on -> shownC = if (on) shownC + c.id else shownC - c.id }
                    }
                    trackers.forEach { t ->
                        SwitchRow("${t.name} (zdarzenie)", t.id in shownT) { on -> shownT = if (on) shownT + t.id else shownT - t.id }
                    }
                }
                Text(
                    "Co pokazywać w stałych powiadomieniach liczników i zdarzeń. Nazwa i dzisiejsza wartość są zawsze.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NotifField.Group.entries.forEach { g ->
                    Text(g.title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    NotifField.entries.filter { it.group == g }.forEach { field ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { fields = if (field in fields) fields - field else fields + field },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = field in fields,
                                onCheckedChange = { on -> fields = if (on) fields + field else fields - field },
                            )
                            Text(field.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                SwitchRow("Na ekranie blokady tylko nazwy", hideOnLock) { hideOnLock = it }
                TextButton(onClick = { fields = NotifField.defaults }) { Text("Przywróć domyślne") }
                HorizontalDivider()
                Text("Kopia zapasowa", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onKoofr) { Text("Kopia na rodzinnym Koofr…") }
                HorizontalDivider()
                TextButton(onClick = {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Systemowe ustawienia powiadomień") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Prefs.save(ctx, remOn, remMin, overdue, goalNotify)
                Prefs.saveNotif(ctx, fields, hideOnLock)
                counters.filter { (it.id in shownC) != it.notify }.forEach { Repo.saveCounter(it.copy(notify = it.id in shownC)) }
                trackers.filter { (it.id in shownT) != it.notify }.forEach { Repo.saveTracker(it.copy(notify = it.id in shownT)) }
                Notifier.refresh(ctx)
                Alarms.scheduleAll(ctx)
                onDismiss()
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

/* ---------- powrót po zamknięciu aplikacji ---------- */

/** Pokazywany po ponownym uruchomieniu, gdy zdarzenia nadal trwają. */
@Composable
fun ResumeDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val v by Repo.version.collectAsState()
    val running = remember(v) { Repo.running() }
    val trackers = remember(v) { Repo.trackers().associateBy { it.id } }
    val pauses = remember(v) { Repo.pausesFor(running.map { it.id }) }
    LaunchedEffect(running.isEmpty()) { if (running.isEmpty()) onDismiss() }
    val now = System.currentTimeMillis()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Witaj z powrotem") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Aplikacja była zamknięta, ale te zdarzenia nadal się liczą. Sprawdź, czy wszystko się zgadza.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                running.forEach { s ->
                    val t = trackers[s.trackerId]
                    val ps = pauses[s.id] ?: emptyList()
                    val paused = ps.any { it.end == null }
                    val active = Stats.activeMs(s, ps, now)
                    HorizontalDivider()
                    Text(t?.name ?: "Zdarzenie", fontWeight = FontWeight.SemiBold)
                    Text(
                        (if (paused) "Pauza · " else "Trwa · ") + "${Dates.dur(active)} (od ${Dates.dateTime(s.start)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { if (paused) Repo.resumeSession(s.id) else Repo.pauseSession(s.id) }) {
                            Text(if (paused) "Wznów" else "Pauza")
                        }
                        TextButton(onClick = { Repo.stopSession(s.id) }) { Text("Zatrzymaj teraz") }
                        TextButton(onClick = {
                            pickDateTime(ctx, now) { endTs ->
                                if (endTs > s.start) Repo.saveSession(s.copy(end = endTs))
                            }
                        }) { Text("Zakończ o…") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Kontynuuj") } },
    )
}
