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

### Sposób 1: GitHub buduje APK (bez instalowania niczego)

1. Załóż na github.com **prywatne** repozytorium, np. `kreska`.
2. Wgraj do niego całą zawartość tego folderu (Add file → Upload files; przeciągnij wszystko, łącznie z ukrytym folderem `.github`). Jeśli przeglądarka pominie `.github`, utwórz plik `.github/workflows/build.yml` ręcznie i wklej jego treść.
3. Wejdź w zakładkę **Actions** – budowanie startuje samo (ok. 5 min).
4. Po zielonym ✓ otwórz przebieg, na dole w **Artifacts** pobierz `kreska-apk` (zip z plikiem `app-debug.apk`).
5. Przenieś APK na telefon, otwórz go i zezwól na instalację z nieznanego źródła.

### Sposób 2: Android Studio

1. Otwórz ten folder w Android Studio (File → Open).
2. Podłącz telefon z włączonym debugowaniem USB i naciśnij **Run**.
   Albo: Build → Build APK(s).

## Uwagi

- Przy pierwszym uruchomieniu (Android 13+) zgódź się na powiadomienia.
- Od Androida 14 stałe powiadomienia da się zsunąć palcem. Wrócą przy następnym
  kliknięciu w aplikacji lub po otwarciu aplikacji.
- Przypomnienia korzystają z alarmów bez specjalnych uprawnień, więc w trybie
  oszczędzania baterii mogą przyjść kilka minut później.
- Eksport CSV (zakładka Raporty) używa średnika i otwiera się poprawnie w polskim Excelu.
