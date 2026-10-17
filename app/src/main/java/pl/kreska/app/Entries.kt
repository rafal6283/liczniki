package pl.kreska.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/** Który wpis edytujemy: null = nowy. */
private data class TapEdit(val tap: Tap?)

/** Lista wystąpień licznika w wybranym dniu: dodawanie, edycja i usuwanie pojedynczych wpisów. */
@Composable
fun EntriesDialog(c: Counter, initialDay: LocalDate, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val v by Repo.version.collectAsState()
    var day by remember { mutableStateOf(initialDay) }
    val taps = remember(v, day) { Repo.tapsForDay(c.id, day) }
    val total = taps.sumOf { it.amount }
    val col = Palette.color(c.color)
    val today = LocalDate.now()
    var editing by remember { mutableStateOf<TapEdit?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(col)
                    Spacer(Modifier.width(8.dp))
                    Text("Wpisy: ${c.name}")
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { day = day.minusDays(1) }) { Text("‹", fontSize = 22.sp) }
                    Column(
                        Modifier.weight(1f).clickable { pickDate(ctx, day) { day = it } },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(if (day == today) "Dziś" else Dates.day(day), fontWeight = FontWeight.SemiBold)
                        Text(
                            "suma ${Num.fmt(total)}" + (c.goal?.let { " / ${Num.fmt(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { day = day.plusDays(1) }, enabled = day < today) { Text("›", fontSize = 22.sp) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    if (taps.isEmpty()) {
                        Text(
                            "Brak wpisów tego dnia.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    taps.forEach { t -> TapRow(t, c) { editing = TapEdit(t) } }
                }
                Button(onClick = { editing = TapEdit(null) }, modifier = Modifier.fillMaxWidth()) { Text("+ Dodaj wpis") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zamknij") } },
    )

    editing?.let { e -> TapDialog(c, e.tap, day) { editing = null } }
}

@Composable
private fun TapRow(t: Tap, c: Counter, onEdit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(Dates.hm(t.ts), fontFamily = FontFamily.Monospace, modifier = Modifier.width(56.dp))
        Text(
            when {
                t.manual -> "korekta dnia"
                t.amount < 0 -> "cofnięcie"
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            (if (t.amount > 0) "+" else "−") + Num.fmt(kotlin.math.abs(t.amount)),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            color = if (t.amount > 0) Palette.color(c.color) else MaterialTheme.colorScheme.error,
        )
        Icon(
            Icons.Filled.Edit,
            contentDescription = "Edytuj wpis",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp).size(16.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Dodanie lub edycja pojedynczego wpisu: data i godzina oraz ilość. */
@Composable
private fun TapDialog(c: Counter, tap: Tap?, day: LocalDate, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val initialTs = remember {
        tap?.ts ?: run {
            val now = System.currentTimeMillis()
            if (day == LocalDate.now()) now else Dates.startOf(day) + 12 * 3_600_000L
        }
    }
    var ts by remember { mutableLongStateOf(initialTs) }
    var amount by remember { mutableStateOf((tap?.amount ?: c.step).toString()) }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tap == null) "Nowy wpis" else "Edytuj wpis") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DateTimeField("Kiedy", Dates.dateTime(ts)) { pickDateTime(ctx, ts) { ts = it } }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val x = (amount.toIntOrNull() ?: 0) - 1
                        amount = x.toString()
                    }) { Text("−") }
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { s -> amount = s.filterIndexed { i, ch -> ch.isDigit() || (i == 0 && ch == '-') }.take(7) },
                        label = { Text("Ilość") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        val x = (amount.toIntOrNull() ?: 0) + 1
                        amount = x.toString()
                    }) { Text("+") }
                }
                if (tap?.manual == true) {
                    Text(
                        "To korekta z „Ustaw wartość dnia”. Po zapisaniu stanie się zwykłym wpisem o wybranej godzinie.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ErrorText(err)
                if (tap != null) {
                    DeleteButton("Usuń wpis", "Na pewno usunąć?") {
                        Repo.deleteTap(tap.id)
                        onDismiss()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = amount.toIntOrNull()
                when {
                    a == null || a == 0 -> err = "Podaj ilość różną od zera."
                    ts > System.currentTimeMillis() + 60_000L -> err = "Wpis nie może być w przyszłości."
                    else -> {
                        if (tap == null) Repo.addTap(c.id, ts, a) else Repo.updateTap(tap.id, ts, a)
                        onDismiss()
                    }
                }
            }) { Text("Zapisz") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}
