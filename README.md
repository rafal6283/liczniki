# Kreska — aplikacja na Androida

Liczniki powtórzeń, zdarzenia ze startem i końcem, raporty z wykresami.
Działa offline, dane są tylko na telefonie (SQLite). Android 8.0 lub nowszy.

## Co jest w powiadomieniach

- **Licznik** – stałe powiadomienie z dzisiejszym wynikiem, paskiem celu i przyciskami **−** i **+**. Działa z panelu powiadomień i z ekranu blokady, bez otwierania aplikacji.
- **Typ zdarzenia** – przycisk **Start**. Po starcie powiadomienie zmienia się w stoper z przyciskiem **Stop**.
- **Cel osiągnięty** – gdy licznik dojdzie do celu dziennego.
- **Przypomnienie o celach** – o wybranej godzinie, jeśli cel nie jest osiągnięty (włączasz w Ustawieniach, ikona zębatki).
- **Niezatrzymane zdarzenie** – gdy zdarzenie trwa dłużej niż ustawiony czas (domyślnie 4 h), z przyciskiem „Zatrzymaj teraz”.

Każdy licznik i typ zdarzenia ma przełącznik „Pokaż w powiadomieniach”. Kanały
powiadomień (np. wyciszenie przypomnień) zmienisz w ustawieniach systemu.

## Jak zainstalować

Każda zmiana w gałęzi `main` buduje nową wersję i publikuje ją w zakładce
**Releases** tego repozytorium.

1. Na telefonie zaloguj się na github.com i otwórz **Releases** → najnowsza wersja.
2. Pobierz **kreska.apk** i otwórz plik. Zezwól na instalację z tego źródła.
3. Kolejne wersje instaluj tak samo — zastępują poprzednią, dane zostają
   (wszystkie wersje są podpisane tym samym kluczem `app/kreska.keystore`).

Alternatywnie: otwórz folder w Android Studio i naciśnij **Run**.

## Uwagi

- Przy pierwszym uruchomieniu (Android 13+) zgódź się na powiadomienia.
- Od Androida 14 stałe powiadomienia da się zsunąć palcem. Wrócą przy następnym
  kliknięciu w aplikacji lub po otwarciu aplikacji.
- Przypomnienia korzystają z alarmów bez specjalnych uprawnień, więc w trybie
  oszczędzania baterii mogą przyjść kilka minut później.
- Eksport CSV (zakładka Raporty) używa średnika i otwiera się poprawnie w polskim Excelu.
