# Turf War

## Build
Run `./build.sh` on macOS or Linux, or `build.bat` on Windows (Java 17 or later).
This compiles everything and writes two jars into `dist`:
- dist/TurfWarClient.jar
- dist/TurfWarServer.jar

## Run
Server terminal:
    java -jar dist/TurfWarServer.jar 5050

Client terminal (one per player):
    java -jar dist/TurfWarClient.jar localhost 5050 Alice (change username for other client)

If run on other computer, replace `localhost` with the server computer's IP address
(on macOS: `ipconfig getifaddr en0`). The client arguments only pre-fill the
start dialog and can be left out.

## Code layout

| Package | Contents | Changed? |
|---|---|---|
| `turfwar.api` | `GameController` (called by the window) and `GameView` (called by the client). | Unchanged scaffold |
| `turfwar.model` | `Config` rule constants, the shared types `Tool`, `Phase`, `Square`, `PlayerInfo`, and the shapes in `Geometry`. | Unchanged scaffold |
| `turfwar.ui` | The provided window and its panels. | Unchanged scaffold |
| `turfwar.demo` | `DemoController`, kept for the "Local demo" option. | Unchanged scaffold |
| `turfwar.net` | The shared protocol: remote interfaces `TWService` (client to server) and `ClientCallback` (server to client), the serialisable DTOs `PlayerDTO` and `SquareChange`, and the constants and RMI timeouts in `Protocol`. | New |
| `turfwar.server` | `ServerMain` creates the registry and binds the service. `TWServiceImplementation` holds the match state behind one lock and runs the ticker thread. `ClientLink` is one outgoing mailbox and delivery thread per client. `RoundClock` is the pause-aware round clock. | New  |
| `turfwar.client` | `NetworkController` implements `GameController`, sends requests on its outbox thread, sends heartbeats and plays replays. `CallbackImplementation` implements `ClientCallback`. `ServerConnection` finds and checks the server. | New |
| `turfwar.Main` | Client entry point: shows the start dialog and creates the `NetworkController`; shows the reason and asks again if connecting fails. | Changed |

