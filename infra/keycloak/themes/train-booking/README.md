# Keycloak login theme "train-booking"

The sign-in, registration, forgot-password, error, information and required-action pages of Keycloak, in the look of the web app
([docs/design/DESIGN.md](../../../../docs/design/DESIGN.md)). Login pages only: the account console and the admin console keep their own themes.

## How it is built

- `login/theme.properties`: `parent=keycloak.v2` and `styles=css/styles.css css/train-booking.css`. The built-in theme does all the work
  (every page, every field, every validation message); this theme only restyles it. `darkMode=false` keeps the pages light.
- `login/resources/css/train-booking.css`: the whole design, CSS only. The page is split: the realm's header element is the photo panel (its
  `::before` is the picture with the slow drift, its `::after` the dark scrim, its own pseudo-elements the wordmark and the headline) and the main
  card is the form. Under 900 px the photo becomes a band above the form. `prefers-reduced-motion` switches the drift and the entrance off.
- `login/messages/messages_en.properties`: the wording of titles, buttons and links (sentence case). Keycloak's field names and its validation
  and error messages are not changed.
- `login/resources/img/`: copies of `hero.jpg` and `logo-etisalat-white.png`, the placeholders of the web app (`frontend/public/brand/`). Replace them
  there and here when the licensed photo and the official logo arrive.

No Keycloakify and no template overrides: nothing to build.

## Running it

`docker-compose.yml` mounts `infra/keycloak/themes` into `/opt/keycloak/themes`, and `realm-dev.json` sets `"loginTheme": "train-booking"`.
Keycloak runs in `start-dev`, which does not cache themes: save a file and reload the page.

The realm file is imported only when the realm does not exist yet. For a realm that already exists, set the theme once: Keycloak admin console,
realm **train-booking**, Realm settings, Themes, Login theme **train-booking**.
