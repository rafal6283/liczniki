package pl.kreska.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/* ---------- porównanie z Nocnym licznikiem i Polar EKG ---------- */

private fun pct(x: Double?): String = if (x == null) "–" else "${(x * 100).roundToInt()}%"

private fun durOrDash(ms: Long): String = if (ms > 0) Dates.dur(ms) else "–"

private fun delayText(ms: Long?): String = when {
    ms == null -> "–"
    ms >= 0 -> Dates.dur(ms) + " po"
    else -> Dates.dur(abs(ms)) + " przed"
}

/** Wszystko, czego potrzebują karta i ekran szczegółów (liczone raz na zmianę danych). */
class CompareData(
    val counter: Counter?,
    val tracker: Tracker?,
    val sources: Map<String, Pair<Long, Int>>,
    val farts: FartComparison?,
    val af: AfComparison?,
    val tolSec: Int,
)

@Composable
fun rememberCompare(v: Long, from: LocalDate, to: LocalDate, cfg: Int): CompareData {
    val ctx = LocalContext.current
    return remember(v, from, to, cfg) {
        val counters = Repo.counters()
        val trackers = Repo.trackers()
        val cid = ComparePrefs.counterId(ctx, counters)
        val tid = ComparePrefs.trackerId(ctx, trackers)
        val src = Repo.extSources()
        val tol = ComparePrefs.tolSec(ctx)
        CompareData(
            counter = counters.firstOrNull { it.id == cid },
            tracker = trackers.firstOrNull { it.id == tid },
            sources = src,
            farts = if (cid != null && src.containsKey(SRC_NIGHT)) Compare.farts(cid, from, to, tol) else null,
            af = if (tid != null && src.containsKey(SRC_ECG)) Compare.af(tid, from, to) else null,
            tolSec = tol,
        )
    }
}

@Composable
private fun SourcesLine(d: CompareData) {
    val parts = listOf(SRC_NIGHT, SRC_ECG).map { s ->
        val info = d.sources[s]
        Exchange.sourceLabel(s) + ": " + if (info == null) "brak importu" else "eksport ${Dates.dateTime(info.first)}"
    }
    Text(parts.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun FartSummary(d: CompareData) {
    val f = d.farts
    when {
        d.counter == null -> Hint("Wybierz licznik FF w ustawieniach porównania.")
        f == null -> Hint("Zaimportuj plik z Nocnego licznika (Ustawienia → Eksport wiatrów do Kreski).")
        else -> StatsGrid(
            listOf(
                "${f.combined}" to "łącznie (bez podwójnego liczenia)",
                "${f.matched}" to "wspólne (±${d.tolSec} s)",
                "${f.detected - f.matched}" to "tylko detektor w nocy",
                "${f.manual - f.matched}" to "tylko Twój wpis w nocy",
                "${f.manualOutside}" to "wpisy w dzień (poza nocami)",
                "${pct(f.caughtShare)} / ${pct(f.confirmedShare)}" to "detektor złapał / wykryć potwierdzonych",
            )
        )
    }
}

@Composable
private fun AfSummary(d: CompareData) {
    val a = d.af
    when {
        d.tracker == null -> Hint("Wybierz zdarzenie AFIB w ustawieniach porównania.")
        a == null -> Hint("Zaimportuj plik z Polar EKG (Zapisy → ikona eksportu).")
        else -> StatsGrid(
            listOf(
                durOrDash(a.combinedMs) to "łącznie (EKG + Kreska bez zapisu)",
                "${durOrDash(a.ecgMs)} · ${pct(a.burden)}" to "AF w EKG · obciążenie",
                durOrDash(a.bothMs) to "wspólnie EKG i Kreska",
                durOrDash(a.ecgOnlyMs) to "tylko EKG (bez odczucia)",
                durOrDash(a.kreskaOnlyInWindowsMs) to "wpis w Kresce, EKG bez AF",
                durOrDash(a.kreskaOutsideMs) to "wpis w Kresce bez zapisu EKG",
                "${a.felt}/${a.ecgEpisodes}" to "epizody EKG odczute",
                "${a.confirmed}/${a.kreskaCovered}" to "wpisy potwierdzone w EKG",
            )
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun CompareCard(v: Long, from: LocalDate, to: LocalDate, cfg: Int, onImport: () -> Unit, onSettings: () -> Unit, onOpen: () -> Unit) {
    val d = rememberCompare(v, from, to, cfg)
    AppCard {
        Eyebrow("Porównanie z aplikacjami")
        Text("${d.counter?.name ?: "FF"} ↔ wiatry z Nocnego licznika", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FartSummary(d)
        HorizontalDivider()
        Spacer(Modifier.height(10.dp))
        Text("${d.tracker?.name ?: "AFIB"} ↔ migotanie w Polar EKG", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AfSummary(d)
        SourcesLine(d)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport) { Text("Importuj plik") }
            TextButton(onClick = onSettings) { Text("Ustawienia") }
        }
        if (d.farts != null || d.af != null) {
            TextButton(onClick = onOpen, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Noc po nocy, dzień po dniu →") }
        }
    }
}

@Composable
fun CompareDetail(v: Long, days: List<LocalDate>, range: Int, onRange: (Int) -> Unit, cfg: Int, onBack: () -> Unit) {
    val d = rememberCompare(v, days.first(), days.last(), cfg)
    val f = d.farts
    val a = d.af
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("← Raporty") }
                Eyebrow("Porównanie z aplikacjami")
                Text("Szczegóły", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                RangeChips(range, onRange)
            }
        }
        item {
            StatSection("${d.counter?.name ?: "FF"} ↔ wiatry", "Za ${if (range == 365) "rok" else "$range dni"}. Dopasowanie ±${d.tolSec} s.") {
                FartSummary(d)
            }
        }
        if (f != null && f.windows.isNotEmpty()) {
            item {
                StatSection("Noce", "detektor · Twoje wpisy · wspólne → łącznie") {
                    Column {
                        f.windows.asReversed().forEach { w ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(
                                    "${Dates.dayOf(w.window.end)}  ${Dates.hm(w.window.start)}–${Dates.hm(w.window.end)}",
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${w.detected} · ${w.manual} · ${w.matched} → ${w.combined}",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            StatSection("${d.tracker?.name ?: "AFIB"} ↔ migotanie", "EKG liczy się tylko w czasie nagrywania.") {
                AfSummary(d)
                if (a != null) {
                    InfoLine("Nagrywanie EKG w okresie", durOrDash(a.coverageMs))
                    InfoLine("Wpis w Kresce względem startu AF (mediana)", delayText(a.medianDelayMs))
                }
            }
        }
        if (a != null && a.days.isNotEmpty()) {
            item {
                StatSection("Dni", "EKG · Kreska · wspólnie") {
                    Column {
                        a.days.forEach { day ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                                Text(Dates.day(LocalDate.parse(day.day)), modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    "${durOrDash(day.ecgMs)} · ${durOrDash(day.kreskaMs)} · ${durOrDash(day.bothMs)}",
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Wpisy FF w nocy nie są pełną prawdą (śpiąc nie klikasz), więc „tylko detektor” to nie zawsze błąd. " +
                    "Odrzucone w Nocnym liczniku (✗) wiatry są pominięte. Korekty dnia bez godziny nie biorą udziału w dopasowaniu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun CompareSettingsDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    val counters = remember { Repo.counters() }
    val trackers = remember { Repo.trackers() }
    var cid by remember { mutableLongStateOf(ComparePrefs.counterId(ctx, counters) ?: -1L) }
    var tid by remember { mutableLongStateOf(ComparePrefs.trackerId(ctx, trackers) ?: -1L) }
    var tol by remember { mutableIntStateOf(ComparePrefs.tolSec(ctx)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Porównanie z aplikacjami") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Licznik do porównania z wiatrami", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    counters.forEach { c -> FilterChip(selected = cid == c.id, onClick = { cid = c.id }, label = { Text(c.name) }) }
                }
                Text("Zdarzenie do porównania z migotaniem w EKG", fontWeight = FontWeight.SemiBold)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    trackers.forEach { t -> FilterChip(selected = tid == t.id, onClick = { tid = t.id }, label = { Text(t.name) }) }
                }
                HorizontalDivider()
                Text("Dopasowanie wpisu FF do wykrycia", fontWeight = FontWeight.SemiBold)
                Text(
                    "Jak daleko w czasie mogą być od siebie, żeby liczyć je jako ten sam wiatr.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(30, 60, 120, 300).forEach { s ->
                        FilterChip(selected = tol == s, onClick = { tol = s }, label = { Text(if (s < 60) "±$s s" else "±${s / 60} min") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (cid >= 0) ComparePrefs.setCounter(ctx, cid)
                if (tid >= 0) ComparePrefs.setTracker(ctx, tid)
                ComparePrefs.setTol(ctx, tol)
                onSaved()
                onDismiss()
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
