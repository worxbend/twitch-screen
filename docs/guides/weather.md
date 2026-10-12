# Weather from the relay

The relay can fetch current weather from [Open-Meteo](https://open-meteo.com/en/docs). One backend fetch supplies every connected display; neither the ESP32 nor the companion calls the weather provider. Open-Meteo current conditions are weather-model estimates, not a measurement made by your device.

## Enable the backend

In your private relay environment file, for example `twitch-screen-relay/.env`, set:

```dotenv
RELAY_WEATHER_ENABLED=true
RELAY_WEATHER_LATITUDE=50.45466
RELAY_WEATHER_LONGITUDE=30.5238
RELAY_WEATHER_LOCATION=Kyiv
RELAY_WEATHER_UNIT=celsius
RELAY_WEATHER_REFRESH_INTERVAL="5 minutes"
RELAY_WEATHER_FRESHNESS="15 minutes"
RELAY_WEATHER_REQUEST_TIMEOUT="10 seconds"
```

These coordinates are Kyiv City, Ukraine, from the provider's geocoding API. Change latitude, longitude and label together for another location. The supported temperature units are `celsius` and `fahrenheit`. Provider configuration is read at startup, not changed by selecting a dashboard in the admin panel. The shipped `.env.example` keeps fetching disabled, so generic smoke tests need no external provider.

Recreate your existing Compose service with the same project name, volume and network overrides after changing environment variables. For a new default loopback installation, from `twitch-screen-relay/`:

```sh
python3 tools/compose.py --env-file .env up --build -d relay
curl --fail http://localhost:8080/api/v1/weather
```

For an existing named deployment, retain its `-p` and `-f` arguments. A plain `docker compose restart` does not apply environment-file changes. Keep the environment file private and never print expanded Compose configuration. Native Mill/JAR launches require explicitly exported environment variables; they do not automatically load `.env`.

The backend fetches on startup and then at the configured cadence. This is provider polling because Open-Meteo offers an HTTP request API, not a live subscription. It is independent of Twitch monitoring and notification capture. Successful fetches enter the relay's existing latest-weather slot with `sourceId: "open-meteo"`; `GET /api/v1/weather` and the admin snapshot expose temperature, condition, humidity, location and observation time.

## Select the screen

Open the private relay admin panel and select Weather in Display intent. Disable the Twitch display override if you want weather even while the stream is live. The equivalent request is:

```sh
curl --fail -X PATCH -H 'Content-Type: application/json' \
  -d '{"twitchDisplayEnabled":false,"preferredDashboard":"weather"}' \
  http://localhost:8080/api/v1/display
```

This persists display intent. It does not disable Twitch ingestion, stop a stream or alter the configured channel. Use your deployment's actual private HTTP address rather than localhost from a different machine.

The ESP32 initiates the usual TCP connection on port 8099. The relay pushes the existing DESK/1 `WeatherSnapshot` and display-control messages on that connection, including on reconnect. There is no device HTTP polling and no new weather protocol. A DESK-capable firmware build uses the weather widget to show location, temperature and unit, condition and optional humidity. Notification cards temporarily cover the widget and return to the currently selected, still-fresh base screen when complete. Classic-only firmware remains compatible with classic notifications but cannot display the weather dashboard.

## Freshness and outages

Provider failures do not interrupt Twitch, CPU telemetry or the device listener. A failed request does not renew the last successful value. The weather slot becomes `stale` when its freshness deadline expires; the configured weather base then falls back to the connected orbit instead of presenting expired data as current. If no successful fetch has occurred, the slot is `missing`. A disconnected device uses its disconnected orbit. Fresh backend data and reconnection allow the selected widget to return.

`PUT /api/v1/weather` remains available for an external producer. With the built-in provider enabled, a later successful provider fetch replaces that manually supplied latest value. Disable built-in fetching if an external producer should own the weather slot exclusively.

Check the layers separately:

1. `GET /api/v1/weather`: `status` is `fresh`, `data.sourceId` is `open-meteo`, and location/units match your settings.
2. `GET /api/v1/display`: Weather is preferred and the Twitch override has the intended value.
3. `GET /api/v1/admin/snapshot`: the connected DESK peer has a fresh report, `baseScreen: "weather"` and the applied display revision equals desired revision.
4. Inspect the actual LCD: location, numeric temperature/unit, condition and humidity should match the relay data.

HTTP acceptance, wire traffic and firmware reports are separate from physical LCD appearance. They must not be described as a visual check.

## Provider terms and attribution

The keyless Open-Meteo endpoint is for **non-commercial** use, subject to its [API terms](https://open-meteo.com/en/terms). Do not assume a monetized or commercial deployment is covered by that free-service permission. This integration does not configure the commercial endpoint or take payment.

Weather data: [Open-Meteo](https://open-meteo.com/), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), [provider licence and upstream sources](https://open-meteo.com/en/licence). The relay selects current variables, maps WMO weather codes to its compact condition vocabulary and rounds temperature for the display protocol. Give attribution when redistributing or showing the data publicly.
