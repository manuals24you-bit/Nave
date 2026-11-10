# Drogowskaz – nawigacja offline na danych OpenStreetMap

Aplikacja Android (telefon + Android Auto): mapy offline, własny silnik tras, korekta pozycji GPS,
licznik prędkości, ograniczenia prędkości, zakazy skrętu, ostrzeżenia, tryb dzień/noc, głos (pl-PL).

## 1. Dane map (GitHub Actions)

1. Wrzuć projekt na własne repozytorium GitHub.
2. Actions → **build-data** → Run workflow: wpisz województwa (np. `mazowieckie,pomorskie` lub `all`).
   Zostaw włączone **routing** – tworzy paczkę „Polska – trasy" (graf całej Polski, trasy między województwami).
3. Dane trafiają do wydania `dane`. Adres do aplikacji:
   `https://github.com/<user>/<repo>/releases/download/dane`

## 2. Instalacja przez Android Studio

1. Otwórz folder projektu w Android Studio (Gradle 8.9 pobierze się sam).
2. W `gradle.properties` dodaj: `dataUrl=https://github.com/<user>/<repo>/releases/download/dane`
   (albo wpisz adres w aplikacji: ☰ → Adres serwera map).
3. Telefon: tryb programisty + debugowanie USB → Run ▶.
4. W aplikacji: ☰ → Mapy offline → pobierz swoje województwo + „Polska – trasy".

## 3. Android Auto

- Android Auto → ustawienia → kilka razy dotknij „Wersja" → tryb programisty → ⋮ → **Nieznane źródła**.
- Aplikacja pojawi się w kategorii nawigacji. Test bez auta: Desktop Head Unit (SDK Manager → Android Auto Desktop Head Unit).
- Zalecana kolejność: najpierw sprawdź telefon, potem DHU.

## 4. Funkcje

- Trasy: auto najszybsze / najkrótsze, rower, pieszo (ścieżki, ścieżki rowerowe).
- Zakazy skrętu z OSM (no_*/only_*), ronda, zakazy wjazdu, ruch jednokierunkowy.
- Ostrzeżenia: fotoradary i przejazdy kolejowe z OSM + własne zgłoszenia (⚠, lokalnie, wygasają).
- Zapisane miejsca, dom/praca, zapisane trasy.

## 5. Ograniczenia

- Kod nie był kompilowany w środowisku, w którym powstał – możliwe drobne błędy kompilacji.
- Android Auto nieprzetestowane na prawdziwym aucie.
- Korki na żywo i zgłoszenia społeczności wymagają serwera – nie ma darmowych danych w OSM. Zgłoszenia są na razie tylko lokalne.
- Wszystkie ostrzeżenia z OSM zależą od kompletności danych w danym regionie.
