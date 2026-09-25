# twitch-screen-firmware

ESP32 firmware (PlatformIO, Arduino framework).

From this directory, create the local configuration:

```sh
cp src/credentials.example.h src/credentials.h
```

Edit `src/credentials.h` with your WiFi credentials, the relay/simulator's LAN
address and a device ID. This file is ignored by Git. The LCD wiring is listed
in [PLAN.md](PLAN.md).

Then build or flash the connected ESP32:

```sh
pio run                     # build
pio run -t upload           # flash
pio device monitor          # serial monitor
```

The `test/` directory is a placeholder; no firmware unit tests are implemented
yet. For simulated events, run `python3 demo-server/twitch_server.py` from the
monorepo root. The TCP protocol is documented in [docs/PROTOCOL.md](docs/PROTOCOL.md).
