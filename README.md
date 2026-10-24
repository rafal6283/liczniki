# Kreska — Android i iOS

Liczniki powtórzeń, zdarzenia ze startem i końcem, raporty z wykresami.
Działa offline, dane są tylko na telefonie (SQLite). Android 8.0 lub nowszy.

## Co jest w powiadomieniach

- **Licznik** – stałe powiadomienie z dzisiejszym wynikiem, paskiem celu i przyciskami **−** i **+**. Działa z panelu powiadomień i z ekranu blokady, bez otwierania aplikacji.
- **Typ zdarzenia** – przycisk **Start**. Po starcie powiadomienie zmienia się w stoper z przyciskami **Pauza** i **Stop** (w pauzie: **Wznów**). Stoper pokazuje czas bez pauz.
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

## Wpisy licznika

Na karcie licznika „Wpisy · dodaj / edytuj” otwiera listę wystąpień danego dnia
(strzałki lub dotknięcie daty zmieniają dzień). Możesz dopisać wystąpienie z dowolną
datą i godziną, zmienić godzinę lub ilość albo usunąć wpis. Ręcznie dopisane wpisy liczą
się do statystyk godzinowych. Przycisk „−” usuwa ostatni dzisiejszy wpis.
Z raportów: Pełne statystyki → Historia dni → dotknij dnia.

## Statystyki

W zakładce Raporty każda pozycja ma „Pełne statystyki”: podsumowanie z porównaniem do
poprzedniego okresu, serie dni, wykres dzień po dniu i tydzień po tygodniu, pora dnia
(godziny i części dnia), rytm dnia (pierwsze/ostatnie kliknięcie, odstępy), dni tygodnia,
mapa cieplna dzień × godzina, a dla zdarzeń także godziny startu, kiedy zdarzenie trwa,
rozkład długości, przerwy między zdarzeniami i czas pauz.

## Gdy aplikacja zostanie zamknięta

Wszystko zapisuje się od razu w bazie na telefonie, a czas zdarzeń liczony jest od zapisanej
godziny startu — zamknięcie aplikacji ani restart telefonu niczego nie gubi. Jeśli przy
ponownym uruchomieniu coś nadal trwa, a aplikacja nie była używana przez ponad 30 minut
(lub telefon się restartował), pojawi się ekran „Witaj z powrotem” z opcjami: kontynuuj,
pauza, zatrzymaj teraz albo zakończ o wybranej godzinie.

## Uwagi

- Przy pierwszym uruchomieniu (Android 13+) zgódź się na powiadomienia.
- Od Androida 14 stałe powiadomienia da się zsunąć palcem. Wrócą przy następnym
  kliknięciu w aplikacji lub po otwarciu aplikacji.
- Przypomnienia korzystają z alarmów bez specjalnych uprawnień, więc w trybie
  oszczędzania baterii mogą przyjść kilka minut później.
- Eksport CSV (zakładka Raporty) używa średnika i otwiera się poprawnie w polskim Excelu.
- Eksport wpisów z godzinami (zakładka Raporty → „Eksportuj wpisy z godzinami (JSON)”) zapisuje całą historię wybranego licznika w formacie „wymiana-zdarzen” v1 (źródło `kreska`): każde kliknięcie z godziną plus sumy dni. Ten plik wczytuje np. Dziennik talerza (licznik FF), żeby wiązać wiatry z posiłkami co do godziny.


# Wersja na iPhone'a (iOS 17 lub nowszy)

Kod jest w folderze `ios/` (SwiftUI). Każda zmiana w tym folderze buduje plik
**Kreska.ipa** i publikuje go w **Releases** jako `ios-v1.0.N`.

## Co działa inaczej niż na Androidzie

- Zamiast stałego powiadomienia z + i − są **widżety** (ekran główny i ekran blokady):
  - **Licznik** – jeden wybrany licznik z + i − (przytrzymaj widżet → „Edytuj widżet”, aby wybrać licznik),
  - **Liczniki** – kilka liczników naraz,
  - **Zdarzenia** – Start, Pauza/Wznów, Stop.
- Trwające zdarzenie to **Live Activity**: stoper na ekranie blokady i w Dynamic Island
  z przyciskami Pauza i Stop.
- Przypomnienia o celach i o niezatrzymanym zdarzeniu działają jak na Androidzie.
- Dane są tylko na iPhonie i nie łączą się z wersją na Androida.

## Instalacja z darmowym Apple ID (Sideloadly)

Potrzebny jest komputer (Windows lub Mac) i kabel do iPhone'a.

1. **Windows:** zainstaluj iTunes i iCloud w wersji ze strony Apple (nie ze Sklepu Microsoft).
   **Mac:** nic nie trzeba.
2. Pobierz i zainstaluj **Sideloadly** ze strony sideloadly.io.
3. Pobierz **Kreska.ipa** z najnowszego wydania `ios-v…` w zakładce Releases.
4. Podłącz iPhone'a kablem i na telefonie potwierdź „Ufaj temu komputerowi”.
5. W Sideloadly przeciągnij plik IPA, wpisz swoje Apple ID i naciśnij **Start**
   (poda kod z weryfikacji dwuetapowej, jeśli trzeba).
6. Na iPhonie: **Ustawienia → Prywatność i ochrona → Tryb dewelopera → włącz** (telefon się
   uruchomi ponownie).
7. Na iPhonie: **Ustawienia → Ogólne → VPN i zarządzanie urządzeniem →** Twoje Apple ID → **Ufaj**.

### Co 7 dni

Aplikacja podpisana darmowym Apple ID działa 7 dni. Potem zainstaluj ją ponownie tak samo
(lub włącz w Sideloadly automatyczne odświeżanie przez Wi-Fi). **Nie usuwaj aplikacji
z telefonu** — przy ponownej instalacji dane zostają.

Ograniczenia darmowego konta: najwyżej 3 takie aplikacje naraz i 10 identyfikatorów na
tydzień (Kreska zużywa 2: aplikacja + widżety).

### Gdy widżety nie widzą danych

W aplikacji: Ustawienia (zębatka) → „Dane wspólne z widżetami”. Jeśli jest „nie”,
narzędzie instalacyjne nie przekazało aplikacji wspólnego folderu z widżetami. Aplikacja
działa wtedy normalnie, ale widżety są puste. Pomaga zwykle instalacja nowszą wersją
Sideloadly albo przez AltStore.
