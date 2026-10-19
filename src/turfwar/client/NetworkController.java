package turfwar.client;

import turfwar.api.GameController;
import turfwar.api.GameView;
import turfwar.model.Tool;
import turfwar.net.ClientCallback;
import turfwar.net.TWService;
import turfwar.ui.MainWindow;
import turfwar.ui.StartDialog;
import turfwar.Main;
import turfwar.net.Protocol;

import java.io.File;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import turfwar.model.Config;
import turfwar.model.Phase;
import turfwar.model.PlayerInfo;
import turfwar.model.Square;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * The client’s controller that talks to the server and updates the view.
 * It implements GameController, called by the view when the player does something.
 * It sends each request to the server on the outbox thread.
 * Server updates arrive through CallbackImplementation
 */

public class NetworkController implements GameController{
    private final GameView view;
    private TWService service;
    // step 4
    private volatile String myId; // shared across threads: always read the latest value
    // step 4-3: a queue of jobs, emptied in order by one thread of our own
    private final BlockingQueue<Runnable> outbox = new LinkedBlockingQueue<>();
    // step 6: separate thread, so a slow paint never delays the heartbeat
    private volatile boolean pinging = true; // the heartbeat thread loops while this is true
    // step 12: the last finished round, received from the server 
    private volatile String[] lastRecording;
    private final Object liveLock = new Object();
    private final Square[][] liveArena = new Square[Config.COLS][Config.ROWS];
    private List<PlayerInfo> livePlayers = new ArrayList<>();
    private Phase livePhase = Phase.LOBBY;
    private int liveSeconds = 0;
    private boolean replaying = false;   // only read or changed while holding liveLock
    private Thread replayThread = null;  // only read or changed while holding liveLock
    

    public NetworkController(GameView view) {
        this.view = view;
        // step 12
        for (int c = 0; c < Config.COLS; c++) {
            for (int r = 0; r < Config.ROWS; r++) {
                liveArena[c][r] = Square.NEUTRAL;
            }
        }
        Thread sender = new Thread(() -> runOutbox(), "outbox");
        sender.setDaemon(true); // outbox thread won't decide when the client exits
        sender.start();
    }

    public void setMyId(String id) {
        this.myId = id;
    }
    // step 4-3: runs on the outbox thread, takes one job at a time in the order the player did things
    private void runOutbox() {
        try {
            while (true) {
                Runnable job = outbox.take(); // waits while there is nothing to send
                try {
                    job.run();
                } catch (RuntimeException e) {
                    view.showError("Something went wrong: " + e.getMessage());
                }
                
            }
        } catch (InterruptedException e) {
            // take() requires this, nothing interrupts this thread
        }
    }

    
    
    // step 7: start connecting in the background and return at once 
    public void connect(MainWindow window, StartDialog.Options opt, boolean hosting) {
        view.showInfo("Connecting to " + opt.serverAddress + " port " + opt.port);
        outbox.add(() -> openSession(window, opt, hosting));
            
    }
    // step 3: runs on the outbox thread, allows to wait on the network
    private void openSession(MainWindow window, StartDialog.Options opt, boolean hosting) {
        try {
            // clear message on failure 
            service = ServerConnection.open(opt.serverAddress, opt.port);
            ClientCallback callback = new CallbackImplementation(view, this); // this: NetworkController itself
            String reason;
            if (hosting) {
                reason = service.hostMatch(opt.username, opt.roundSeconds, callback); 
            } else {
                reason = service.requestJoin(opt.username, callback);
            }
            if (reason != null) {
                Main.backToStart(window, opt, reason); // refused: show why, ask again 
                return; 
            }
            startHeartbeat();
        } catch (RemoteException e) {
            // hostMatch / requestJoin failed after we found the server 
            Main.backToStart(window, opt, "The connection to " + opt.serverAddress + opt.port + " failed. Check address and port again.");
        } catch (IOException e) {
            // from ServerConnection.open
            Main.backToStart(window, opt, e.getMessage());
        }
        
    }
    // step 6: thread sleeps 1s, pings, and repeats until pinging becomes false
    private void startHeartbeat() {
        Thread heartbeat = new Thread(() -> {
            while (pinging) {
                try {
                    Thread.sleep(Protocol.PING_INTERVAL_MS);
                } catch (InterruptedException e) {
                    return; 
                }
                try {
                    sendPing();
                } catch (RuntimeException e) {
                    System.err.println("Heartbeat problem: " + e);
                }
            }
        }, "heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }
    private void sendPing() {
        if (myId == null) {
            return; // joined() has not arrived yet
        }
        try {
            service.ping(myId);
        } catch (RemoteException e) {
            pinging = false; // stop pinging, so the message shows only once
            view.showMatchClosed("Lost connection to the server.");
        }
    }
    

    

    // step 4
    // client clicked or dragged over a square with the Paint tool
    @Override 
    public void paint(int col, int row) {
        outbox.add(() -> {
            try {
            service.paint(myId, col, row);
        } catch (RemoteException e) {
            view.showError("No connection to server.");
            }
        });
       
        
    }
    
    // client used a power-up (LINE, BLOCK or WEDGE) anchored at the square
    @Override 
    public void usePowerUp(Tool tool, int col, int row){
        outbox.add(() -> {
            try {
                service.usePowerUp(myId, tool, col, row);
            } catch (RemoteException e) {
                view.showError("No connection to server.");
            }
        });
    }

     // client dropped the Bomb (a fixed 3 x 3 area) centred on the square
    @Override 
    public void useBomb(int col, int row) {
        outbox.add(() -> {
            try {
                service.useBomb(myId, col, row);
            } catch (RemoteException e) {
                view.showError("No connection to server.");
            }
        });
    }

    // step 9: client pressed a taunt button or Ctrl+1 to Ctrl+8. Index into Config.TAUNTS
    @Override 
    public void sendTaunt(int tauntIndex) {
        outbox.add(() -> {
            try {
                service.sendTaunt(myId, tauntIndex);
            } catch (RemoteException e) {
                view.showError("No connection to server.");
            }
        });
    }

    //step 5: host only: Game > Start Round
    @Override 
    public void startRound() {
        outbox.add(() -> {
            try {
                service.startRound(myId);
            } catch (RemoteException e) {
                view.showError("No connection to server.");
            }
        }
    );
    }

    // host only: Game > Pause / Resume (the UI shows one item that toggles)
    @Override 
    public void pauseOrResume() {
        outbox.add(() -> {
            try {
                service.pauseOrResume(myId);
            } catch (RemoteException e) {
                view.showError("No connection to server.");
            }
        });
    }

    // step 8: host only: the host selected a player on the scoreboard and chose Game > Kick
    @Override 
    public void kick(String targetId) {
        outbox.add(() -> {
            try {
                service.kick(myId, targetId);
            } catch (RemoteException e) {
                view.showError("No connection to server.");
            }
        });
    }

    // host only: the host answered a join request shown by {@link GameView#showJoinRequest}
    @Override 
    public void respondToJoin(String requestId, boolean approve) {
        outbox.add(() -> {
            try {
                service.respondToJoin(myId, requestId, approve);
            } catch (RemoteException e) {
                view.showError("No connection to server.");
            }
        });
    }

    // step 6: Game > Leave, or the window was closed. Disconnect cleanly and exit
    @Override 
    public void leave() {
        outbox.add(() -> {
            try {
                service.leave(myId);
            } catch (RemoteException e) {
                view.showError("No connection to server.");
            }
            System.exit(0); // step 3
        });
        
    }

    // host only: Replay > Replay Last Round. Play back the last finished round on this client
    @Override 
    public void replayLastRound() {
        String[] lines = lastRecording;
        if (lines == null) {
            view.showError("There is no finished round to replay yet.");
            return;
        }
        startReplay(lines);
    }

    // host only: Replay > Save Replay. Write the last finished round's log to the file
    @Override 
    public void saveReplay(File file) {
        String[] lines = lastRecording;
        if (lines == null) {
            view.showError("There is no finished round to save yet.");
            return;
        }
        // file work off the Swing thread, so the window never freezes (6.3)
        outbox.add(() -> {
            try {
                Files.write(file.toPath(), Arrays.asList(lines));   // one line per entry, UTF-8
                view.showInfo("Replay saved to " + file.getName());
            } catch (IOException e) {
                view.showError("Could not save the replay: " + e.getMessage());
            }
        });
    }

    
    // host only: Replay > Open Replay. Load a saved log from the file and play it back
    @Override
    public void openReplay(File file) {
        outbox.add(() -> {
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                startReplay(lines.toArray(new String[0]));
            } catch (IOException e) {
                view.showError("Could not open the replay: " + e.getMessage());
            }
        });
    }
    
    // step 12
    // store the last finished round's log, sent to the host only when a round ends
    public void setLastRecording(String[] lines) {
        this.lastRecording = lines;
    }
    // the server sent a square change
    public void liveSquare(int col, int row, Square square) {
        synchronized (liveLock) {
            liveArena[col][row] = square;   
            if (!replaying) {
                view.setSquare(col, row, square);   // only draw it when no replay is on screen
            }
        }
    }
    //  the server sent the scoreboard
    public void livePlayers(List<PlayerInfo> players) {
        synchronized (liveLock) {
            livePlayers = players;
            if (!replaying) {
                view.setPlayers(players);
            }
        }
    }
    // the server sent the current phase and remaining seconds
    public void liveTimer(int seconds, Phase phase) {
        synchronized (liveLock) {
            liveSeconds = seconds;
            livePhase = phase;
            if (!replaying) {
                view.setPhase(phase);
                view.setRemainingSeconds(seconds);
            }
        }
    }

    // start the replay thread, one replay at a time
    private void startReplay(String[] lines) {
        synchronized (liveLock) {
            if (replaying) {
                return;
            }
            replaying = true;
            replayThread = new Thread(() -> runReplay(lines), "replay");
            replayThread.setDaemon(true);
            replayThread.start();
        }
    }

    // the match closed - stop the replay at once
    public void stopReplay() {
        synchronized (liveLock) {
            if (replayThread != null) {
                replayThread.interrupt();   // wakes it from Thread.sleep
            }
        }
    }
    // runs on the replay thread
    private void runReplay(String[] lines) {
        view.setPhase(Phase.REPLAY);   // local display mode: input disabled
        try {
            play(lines);
            view.showInfo("Replay finished.");
        } catch (InterruptedException e) {
            // stopped because the match closed
        } catch (RuntimeException e) {
            view.showError("This is not a valid replay file.");
        } finally {
            // back to the current server state (A4)
            synchronized (liveLock) {
                replaying = false;
                replayThread = null;
                Square[][] copy = new Square[Config.COLS][Config.ROWS];
                for (int c = 0; c < Config.COLS; c++) {
                    for (int r = 0; r < Config.ROWS; r++) {
                        copy[c][r] = liveArena[c][r];
                    }
                }
                view.setPlayers(livePlayers);
                view.setArena(copy);
                view.setPhase(livePhase);
                view.setRemainingSeconds(liveSeconds);
            }
        }
    }
    // read the lines in order and show each event at 4x speed
    private void play(String[] lines) throws InterruptedException {
        if (lines.length == 0 || !lines[0].equals("TURFWAR-REPLAY 1")) {
            throw new IllegalArgumentException("not a replay file");
        }
        // the replay's own arena and players, starting empty
        String[][] owner = new String[Config.COLS][Config.ROWS];
        Map<String, PlayerInfo> players = new LinkedHashMap<>();
        view.setArena(new Square[Config.COLS][Config.ROWS]);   // null = neutral
        view.setPlayers(new ArrayList<>());
        int roundSeconds = 0;
        long start = System.currentTimeMillis();

        for (String line : lines) {
            String[] f = line.split(" ");
            if (f[0].equals("ROUND")) {
                roundSeconds = Integer.parseInt(f[1]);
                view.setRemainingSeconds(roundSeconds);
            } else if (f[0].equals("PLAYER")) {
                // PLAYER offset id colour host name
                waitUntil(start, Long.parseLong(f[1]));
                PlayerInfo p = new PlayerInfo(f[2], f[5], Integer.parseInt(f[3]), Boolean.parseBoolean(f[4]), 0);
                players.put(p.id, p);
                showPlayers(players, owner);
            } else if (f[0].equals("LEAVE")) {
                waitUntil(start, Long.parseLong(f[1]));
                players.remove(f[2]);
                showPlayers(players, owner);
            } else if (f[0].equals("SQUARE")) {
                // SQUARE offset col row state
                long offset = Long.parseLong(f[1]);
                waitUntil(start, offset);
                int col = Integer.parseInt(f[2]);
                int row = Integer.parseInt(f[3]);
                Square square;
                if (f[4].equals("-")) {
                    square = Square.NEUTRAL;
                    owner[col][row] = null;
                } else if (f[4].equals("#")) {
                    square = Square.SCORCHED;
                    owner[col][row] = null;
                } else {
                    square = Square.ownedBy(f[4]);
                    owner[col][row] = f[4];
                }
                view.setSquare(col, row, square);
                showPlayers(players, owner);
                view.setRemainingSeconds(roundSeconds - (int) (offset / 1000));
            } else if (f[0].equals("END")) {
                waitUntil(start, Long.parseLong(f[1]));
                view.setRemainingSeconds(0);
            }
            // SCORE lines: the final standings, already reached by the events above
        }
    }

    // 4x speed - an event at offset t ms is shown t / 4 ms after the replay started
    private void waitUntil(long start, long offsetMs) throws InterruptedException {
        long wait = start + offsetMs / 4 - System.currentTimeMillis();
        if (wait > 0) {
            Thread.sleep(wait);
        }
    }

    // the replay scoreboard - count each player's squares in the replay arena
    private void showPlayers(Map<String, PlayerInfo> players, String[][] owner) {
        Map<String, Integer> count = new HashMap<>();
        for (int c = 0; c < Config.COLS; c++) {
            for (int r = 0; r < Config.ROWS; r++) {
                if (owner[c][r] != null) {
                    count.merge(owner[c][r], 1, Integer::sum);   // +1 for this owner
                }
            }
        }
        List<PlayerInfo> list = new ArrayList<>();
        for (PlayerInfo p : players.values()) {
            list.add(p.withSquares(count.getOrDefault(p.id, 0)));
        }
        view.setPlayers(list);
    }
    
}
