package turfwar.server;

import turfwar.model.Config;
import turfwar.model.Geometry;
import turfwar.model.Phase;
import turfwar.net.ClientCallback;
import turfwar.net.TWService;
import turfwar.net.PlayerDTO;
import turfwar.net.Protocol;
import turfwar.net.SquareChange;
import turfwar.model.Tool;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.awt.Point;
/**
 * Implementation of the TWService interface, which is called by the clients.
 * The server implements the methods to manage the game state and communicate with clients.
 */

public class TWServiceImplementation extends UnicastRemoteObject implements TWService {

    public TWServiceImplementation() throws RemoteException {
        super();
        // step 5: one thread wakes every 0.1 s and runs doTick() under the lock 
        Thread ticker = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return; // asked to stop
                }
                locked(() -> doTick());

            }
        }, "turfwar-ticker");
        ticker.setDaemon(true); // ticker thread won't stop the server from exiting
        ticker.start();
    }

    // step 2
    private final List<PlayerDTO> players = new ArrayList<>();
    private final List<ClientLink> links = new ArrayList<>(); // step 4-2
    private int nextId = 1;
    // step 4
    // null means neutral: nobody owns the square
    private final String[][] owner = new String[Config.COLS][Config.ROWS];
    // step 5-3
    // true = destroyed for the rest of the round 
    private final boolean[][] scorched = new boolean[Config.COLS][Config.ROWS];
    // step 4-2: one lock guards all game state
    // only the thread holding it can read or change the game
    private final Object lock = new Object();

    private Phase phase = Phase.LOBBY;
    private String hostId = null;
    private int roundSeconds = Config.DEFAULT_ROUND_SECONDS;
    // step 10: the round's own clock
    private final RoundClock roundClock = new RoundClock();
    private int lastSentSeconds = -1; // the timer is broadcast once per second
    // remaining uses of each tool, per player 
    private final Map<String, Map<Tool, Integer>> uses = new HashMap<>();
    // last time (ms) heard a ping from each player
    private final Map<String, Long> lastSeen = new HashMap<>();

    // nested class for a join request waiting for the host's answer 
    private static class PendingJoin {
        public final String name;
        public final ClientLink link;

        public PendingJoin(String name, ClientLink link) {
            this.name = name;
            this.link = link;
        }
    }
    private final Map<String, PendingJoin> pending = new HashMap<>(); // key = player id the joiner will get if approved
    // step 9: when each player last sent an accepted taunt
    private final Map<String, Long> lastTaunt = new HashMap<>();
    // step 12: one line per event, in the order the server applied them
    private List<String> recording = new ArrayList<>();

    // step 1, 6
    // heartbeat from a client: record when it was last heard from
    // checkHeartbeats() on the ticker removes anyone silent for too long
    @Override
    public void ping(String playerId) throws RemoteException {
        locked(() -> doPing(playerId));
    }
    private void doPing(String playerId) {
        // only refresh players the server still knows, so a late ping cannot bring back a removed player
        if (lastSeen.containsKey(playerId)) {
            lastSeen.put(playerId, System.currentTimeMillis());
        }
    }

    // step 2, 7
    // first client creates the match and becomes host
    @Override 
    public String hostMatch(String name, int roundSeconds, ClientCallback callback) throws RemoteException {
        // the client(host) needs the answer, so run doHost while holding the lock
        synchronized (lock) {
            try {
                return doHost(name, roundSeconds, callback);
            } catch (RuntimeException e) {
                System.err.println("Server error: " + e);
                return "Server error. Please try again.";
            }
        }

    }
    private String doHost(String name, int seconds, ClientCallback callback) {
        // rule 1: never trust the client's own name check
        if (!Config.validName(name)) {
            return "Username must be 1 to 16 letters, digits or underscores.";
        }
        // rule 2: only one match per server 
        if (hostId != null) {
            return "A match is already being hosted on this server. Choose \"Join a match\" instead.";
        }
        // rule 3: round length 60-600 s 
        if (seconds < Config.MIN_ROUND_SECONDS || seconds > Config.MAX_ROUND_SECONDS) {
            return "The round length must be from " + Config.MIN_ROUND_SECONDS
                    + " to " + Config.MAX_ROUND_SECONDS + " seconds.";
        }
        String id = "player" + nextId;
        nextId = nextId + 1;
        hostId = id;
        roundSeconds = seconds;
        admit(id, name, newLink(id, callback)); // the first player: colour 0
        return null;
    }

    // other client asks to join the existing match and waits for the host's approval
    @Override 
    public String requestJoin(String name, ClientCallback callback) throws RemoteException {
        // the client(joiner) needs the answer, so run doRequestJoin while holding the lock
        synchronized (lock) {
            try {
                return doRequestJoin(name, callback);
            } catch (RuntimeException e) {
                System.err.println("Server error: " + e);
                return "Server error. Please try again.";
            }
        }

    }
    private String doRequestJoin(String name, ClientCallback callback) {
        // rule 1: valid name 
        if (!Config.validName(name)) {
            return "Username must be 1 to 16 letters, digits or underscores.";
        }
        // rule 2: someone must be hosting 
        if (hostId == null) {
            return "Nobody is hosting a match on this server yet. Choose \"Host a match\" to start one.";
        }
        // rule 3: unique, ignoring case 
        if (nameTaken(name)) {
            return "The name " + name + " is already taken. Please choose another.";
        }
        // rule 4: at most 16 players 
        if (players.size() >= Protocol.MAX_PLAYERS) {
            return "The match is full (" + Protocol.MAX_PLAYERS + " players).";
        }
        String id = "player" + nextId;
        nextId = nextId + 1;
        ClientLink link = newLink(id, callback);
        // wait for the host approval
        pending.put(id, new PendingJoin(name, link));
        System.out.println(name + " requests to join as " + id);
        link.send(c -> c.info("Waiting for the host to approve your request."));
        ClientLink hostLink = findLink(hostId);
        hostLink.send(c -> c.joinRequest(id, name));
        return null;
    }

    // one mailbox per client: if a delivery fails, take the lock and forget the client
    // check whether it is still waiting or already admitted
    private ClientLink newLink(String id, ClientCallback callback) {
        return new ClientLink(id, callback, () -> locked(() -> {
            pending.remove(id); // still waiting for approval
            doLeave(id); // already a player
        }));
    }

    // step 4
    // client paints one square
    @Override
    public void paint(String playerId, int col, int row) throws RemoteException {
        // step 4-2: runs on this RMI thread while holding the lock
        locked(() -> doPaint(playerId, col, row));
    }
    private void doPaint(String playerId, int col, int row) {
        // step 6-2: ignore anyone who is not an admitted player 
        if (findLink(playerId) == null) {
            return;
        }
        // only can paint during a running round 
        if (phase == Phase.PAUSED) {
            reject(playerId, "The round is paused.");
            return;
        }
        if (phase != Phase.RUNNING) {
            reject(playerId, "The round is not running.");
            return;
        }

        // case 1: out of bound -> ignore
        if (!Geometry.inArena(col, row)) {
            return;
        }

        // case 2: already owned by same client -> no change
        if (playerId.equals(owner[col][row])) {
            return;
        }
        // case 3: scorched -> nothing happens, not an error 
        if (scorched[col][row]) {
            return;
        }
        // case 4: owned by others or not owned -> change owner
        owner[col][row] = playerId;

        SquareChange[] changes = { new SquareChange(col, row, playerId) };
        recordSquares(changes); // step 12
        PlayerDTO[] everyPlayer = currentPlayers();
        broadcast(c -> c.squaresChanged(changes));
        broadcast(c -> c.playersChanged(everyPlayer));

    }

    // step 5
    // host only: clear the arena and start a new round
    @Override
    public void startRound(String playerId) throws RemoteException {
        locked(() -> doStartRound(playerId));
    }
    private void doStartRound(String playerId) {
        // step 6-2: ignore anyone who is not an admitted player
        if (findLink(playerId) == null) {
            return;
        }
        // rule 1: only the host can start a round
        if (!playerId.equals(hostId)) {
            reject(playerId, "Only host can start a round.");
            return;
        }
        // rule 2: not while a round is already going 
        if (phase == Phase.RUNNING || phase == Phase.PAUSED) {
            reject(playerId, "Current round is not finished yet.");
            return;
        }
        // rule 3: at least 2 players 
        if (players.size() < Config.MIN_PLAYERS) {
            reject(playerId, "At least 2 players are needed to start.");
            return;
        }

        // reset the arena: every owned or scorched square becomes neutral
        List<SquareChange> cleared = new ArrayList<>();
        for (int c = 0; c < Config.COLS; c++) {
            for (int r = 0; r < Config.ROWS; r++) {
                if (owner[c][r] != null || scorched[c][r]) {
                    owner[c][r] = null;
                    scorched[c][r] = false; // step 5-3
                    cleared.add(new SquareChange(c, r, null));
                }
            }
        }
        refillPowerUps(); // step 5-2
        // start the round
        phase = Phase.RUNNING;
        roundClock.start(roundSeconds);
        // step 12: a new recording starts with the players and the (empty) arena
        recording = new ArrayList<>();
        recording.add("TURFWAR-REPLAY 1");
        recording.add("ROUND " + roundSeconds);
        for (PlayerDTO pl : players) {
            recordPlayer(pl);
        }
        recordSquares(fullArena());
        lastSentSeconds = roundSeconds;
        // tell every players (copy fields into locals first)
        SquareChange[] changes = cleared.toArray(new SquareChange[0]);
        PlayerDTO[] evPlayer = currentPlayers();
        int secs = roundSeconds;
        Phase p = phase;
        broadcast(c -> c.squaresChanged(changes));
        broadcast(c -> c.playersChanged(evPlayer));
        broadcast(c -> c.timer(secs, p));
        broadcast(c -> c.info("Round start!!!"));
        for (ClientLink link : links) {
            sendAllowances(link.playerId);
        }
    }

    // runs on the ticker thread every 0.1s, inside locked()
    private void doTick() {
        checkHeartbeats(); // step 6: every phase, not only RUNNING
        // clock only counts during running round
        if (phase != Phase.RUNNING) {
            return;
        }
        if (roundClock.expired()) {
            endRound();
            return;
        }
        // broadcast only when the displayed second changes (once per second)
        int secs = roundClock.remainingSeconds();
        if (secs != lastSentSeconds) {
            lastSentSeconds = secs;
            broadcast(c -> c.timer(secs, Phase.RUNNING));
        }

    }

    // use a power-up (Line, Block or Wedge) anchored at the square
    @Override
    public void usePowerUp(String playerId, Tool tool, int col, int row) throws RemoteException {
        locked(() -> doUsePowerUp(playerId, tool, col, row));
    }
    private void doUsePowerUp(String playerId, Tool tool, int col, int row) {
        // step 6-2: ignore anyone who is not an admitted player 
        if (findLink(playerId) == null) {
            return;
        }
        // rule 1: only during a running round 
        if (phase == Phase.PAUSED) {
            reject(playerId, "The round is paused.");
            return;
        }
        if (phase != Phase.RUNNING) {
            reject(playerId, "The round is not running.");
            return;
        }
        // rule 2: only the three power-ups go through here
        if (!tool.isPowerUp()) {
            reject(playerId, "This tool is not a power-up.");
            return;
        }
        // rule 3: the clicked square must be on the arena
        if (!Geometry.inArena(col, row)) {
            return;
        }
        // rule 4: uses left this round, enforced by the server 
        Map<Tool, Integer> mine = uses.get(playerId);
        if (mine == null) {
            return; // unknown player
        }
        int left = mine.get(tool);
        if (left <= 0) {
            reject(playerId, "No " + tool.label + " left this round.");
            return;
        }
        // consume one use, even if no square changes 
        mine.put(tool, left - 1);
        // take every square of the shape that is not already mine
        List<SquareChange> taken = new ArrayList<>();
        for (Point pt : Geometry.squares(tool, col, row)) {
            if (!playerId.equals(owner[pt.x][pt.y]) && !scorched[pt.x][pt.y]) {
                owner[pt.x][pt.y] = playerId;
                taken.add(new SquareChange(pt.x, pt.y, playerId));
            }
        }
        sendAllowances(playerId);
        SquareChange[] changes = taken.toArray(new SquareChange[0]);
        recordSquares(changes); // step 12
        PlayerDTO[] everyPlayer = currentPlayers();
        broadcast(c -> c.squaresChanged(changes));
        broadcast(c -> c.playersChanged(everyPlayer));
    }

    // use the Bomb on the arena, scorches squares and cannot be refilled
    @Override
    public void useBomb(String playerId, int col, int row) throws RemoteException {
        locked(() -> doUseBomb(playerId, col, row));
    }
    private void doUseBomb(String playerId, int col, int row) {
        // step 6-2: ignore anyone who is not an admitted player 
        if (findLink(playerId) == null) {
            return;
        }
        // rule 1: only during a running round
        if (phase == Phase.PAUSED) {
            reject(playerId, "The round is paused.");
            return;
        }
        if (phase != Phase.RUNNING) {
            reject(playerId, "The round is not running.");
            return;
        }
        // rule 2: the clicked square must be on the arena
        if (!Geometry.inArena(col, row)) {
            return;
        }
        // rule 3: one Bomb per match
        Map<Tool, Integer> mine = uses.get(playerId);
        if (mine == null) {
            return;
        }
        int left = mine.get(Tool.BOMB);
        if (left <= 0) {
            reject(playerId, "You have no Bomb left.");
            return;
        }
        // rule 4: when reach the 30% cap (already scorched squares do not count again)
        List<Point> area = Geometry.squares(Tool.BOMB, col, row);
        int extra = 0;
        for (Point pt : area) {
            if (!scorched[pt.x][pt.y]) {
                extra = extra + 1;
            }
        }
        if (countScorched() + extra > Config.scorchCap()) {
            reject(playerId, "The arena is too damaged for another Bomb.");
            return;
        }
        // all checks passed: consume the Bomb and scorch the area
        mine.put(Tool.BOMB, left - 1);
        List<SquareChange> burnt = new ArrayList<>();
        for (Point pt : area) {
            if (!scorched[pt.x][pt.y]) {
                scorched[pt.x][pt.y] = true;
                owner[pt.x][pt.y] = null; // nobody owns a scorched square
                burnt.add(new SquareChange(pt.x, pt.y, null, true));

            }
        }
        sendAllowances(playerId);
        SquareChange[] changes = burnt.toArray(new SquareChange[0]);
        recordSquares(changes); // step 12
        PlayerDTO[] everyPlayer = currentPlayers();
        broadcast(c -> c.squaresChanged(changes));
        broadcast(c -> c.playersChanged(everyPlayer));

    }


    // step 6: the client chose Game > Leave or closed its window
    @Override
    public void leave(String playerId) throws RemoteException {
        locked(() -> doLeave(playerId));
    }
    private void doLeave(String playerId) {
        // find the player first, then remove
        PlayerDTO gone = null;
        for (PlayerDTO p : players) {
            if (p.id.equals(playerId)) {
                gone = p;
            }
        }
        if (gone == null) {
            return; // already removed
        }
        players.remove(gone);
        ClientLink link = findLink(playerId);
        links.remove(link);
        uses.remove(playerId);
        lastSeen.remove(playerId); // step 6
        lastTaunt.remove(playerId); // step 9
        link.close();
        System.out.println(gone.name + " left.");
        // host leave -> ends the match for everyone 
        if (playerId.equals(hostId)) {
            closeMatch("The host left. The match is over.");
            return;
        }
        // their squares become neutral
        List<SquareChange> freed = new ArrayList<>();
        for (int c = 0; c < Config.COLS; c++) {
            for (int r = 0; r < Config.ROWS; r++) {
                if (playerId.equals(owner[c][r])) {
                    owner[c][r] = null;
                    freed.add(new SquareChange(c, r, null));
                }
            }
        }
        SquareChange[] changes = freed.toArray(new SquareChange[0]);
        recordSquares(changes); // step 12
        record("LEAVE", playerId);
        PlayerDTO[] everyPlayer = currentPlayers();
        String name = gone.name;
        broadcast(c -> c.squaresChanged(changes));
        broadcast(c -> c.playersChanged(everyPlayer));
        broadcast(c -> c.info(name + " left."));

    }

    // the host answered a join request
    @Override
    public void respondToJoin(String playerId, String requestId, boolean approve) throws RemoteException {
        locked(() -> doRespondToJoin(playerId, requestId, approve));
    }
    private void doRespondToJoin(String playerId, String requestId, boolean approve) {
        // step 6-2: ignore anyone who is not an admitted player 
        if (findLink(playerId) == null) {
            return;
        }
        // rule 1: only the host decides 
        if (!playerId.equals(hostId)) {
            reject(playerId, "Only the host can answer join requests.");
            return;
        }
        // rule 2: the request must still be waiting (the joiner may have gone)
        PendingJoin req = pending.remove(requestId);
        if (req == null) {
            return;
        }
        // denied: tell refused client, then close their link
        if (!approve) {
            System.out.println(req.name + " was denied.");
            req.link.send(c -> c.matchClosed("The host denied your request to join."));
            req.link.close();
            return;
        }
        // rule 3: the match is full while the host was deciding 
        if (players.size() >= Protocol.MAX_PLAYERS) {
            req.link.send(c -> c.matchClosed("The match is full."));
            req.link.close();
            return;
        }
        admit(requestId, req.name, req.link);

    }

    // step 8: the host kicked a player out of the match
    @Override
    public void kick(String playerId, String targetId) throws RemoteException {
        locked(() -> doKick(playerId, targetId));
    }
    private void doKick(String playerId, String targetId) {
        // ignore anyone who is not an admitted player
        if (findLink(playerId) == null) {
            return;
        }
        // rule 1: only the host can kick
        if (!playerId.equals(hostId)) {
            reject(playerId, "Only the host can kick player out.");
            return;
        }
        // rule 2: the host cannot kick themselves
        if (playerId.equals(targetId)) {
            reject(playerId, "The host cannot kick themselves.");
            return;
        }
        // rule 3: the target must be in the match
        ClientLink target = findLink(targetId);
        if (target == null) {
            reject(playerId, "That player is not in the match.");
            return;
        }
        // tell the kicked player first
        target.send(c -> c.matchClosed("You were removed from the match by the host."));
        // their squares become neutral everywhere, everyone sees the new scoreboard
        doLeave(targetId);
    }

    // step 9: client clicked a taunt button and send to everyone
    @Override
    public void sendTaunt(String playerId, int tauntIndex) throws RemoteException {
        locked(() -> doSendTaunt(playerId, tauntIndex));
    }
    private void doSendTaunt(String playerId, int tauntIndex) {
        // check if player still in the match (not kick out)
        if (findLink(playerId) == null) {
            return;
        }
        // rule 1: only while a round is running or paused
        if (phase != Phase.RUNNING && phase != Phase.PAUSED) {
            reject(playerId, "Taunts can only be sent while a round is running or paused.");
            return;
        }
        // rule 2: one of the eight fixed taunts
        if (tauntIndex < 0 || tauntIndex >= Config.TAUNTS.length) {
            reject(playerId, "Unknown taunt.");
            return;
        }
        // rule 3: one taunt every 2 seconds per player, drop extra ones and tell the sender
        Long now = System.currentTimeMillis();
        Long last = lastTaunt.get(playerId);
        if (last != null && now - last < Config.TAUNT_MIN_INTERVAL_MS) {
            reject(playerId, "Taunt dropped: only can send one taunt every 2 seconds.");
            return;
        }
        lastTaunt.put(playerId, now);
        // find the sender so everyone can show their name and colour
        PlayerDTO from = null;
        for (PlayerDTO p : currentPlayers()) {
            if (p.id.equals(playerId)) {
                from = p;
            }
        }
        PlayerDTO sender = from;
        String text = Config.TAUNTS[tauntIndex];
        broadcast(c -> c.taunt(sender, text));

    }

    // step 11: host pause or resume the round, tell everyone the new phase and the remaining seconds
    @Override
    public void pauseOrResume(String playerId) throws RemoteException {
        locked(() -> doPauseOrResume(playerId));
    }

    private void doPauseOrResume(String playerId) {
        // ignore anyone who is not an admitted player
        if (findLink(playerId) == null) {
            return;
        }
        // rule 1: check if player is the host
        if (!playerId.equals(hostId)) {
            reject(playerId, "Only the host can pause or resume the round.");
            return;
        }
        // rule 2: check if the round is running
        if (phase == Phase.RUNNING) {
            roundClock.pause();
            phase = Phase.PAUSED;
        } else if (phase == Phase.PAUSED) {
            roundClock.resume();
            phase = Phase.RUNNING;
        } else {
            reject(playerId, "There is no round to pause or resume.");
            return;
        }
        // tell every client the clock and the phase
        int secs = roundClock.remainingSeconds();
        Phase p = phase;
        lastSentSeconds = secs;
        broadcast(c -> c.timer(secs, p));
        if (p == Phase.RUNNING) {
            broadcast(c -> c.info("The host resumed the round."));
        }

    }

    //////////helper methods below this line//////////

    //// step 4:
    // helper method to run one action while holding the lock
    private void locked(Runnable action) {
        synchronized (lock) {
            try {
                action.run();
            } catch (RuntimeException e) {
                System.err.println("Server error: " + e);
            }
        }
    }

    // helper method in currentPlayers()
    private int countSqaure(String playerId) {
        int count = 0;
        for (int c = 0; c < Config.COLS; c++) {
            for (int r = 0; r < Config.ROWS; r++) {
                if (playerId.equals(owner[c][r])) {
                    count = count + 1;
                }
            }
        }
        return count;
    }

    // snapshot of every player with their current square count
    private PlayerDTO[] currentPlayers() {
        PlayerDTO[] result = new PlayerDTO[players.size()];
        for (int i = 0; i < players.size(); i++) {
            PlayerDTO p = players.get(i);
            result[i] = new PlayerDTO(p.id, p.name, p.colourIndex, p.host, countSqaure(p.id));
        }
        return result;
    }

    //// step 5:
    // helper method to send the same message to every client
    private void broadcast(ClientLink.Message message) {
        for (ClientLink link : links) {
            link.send(message);
        }
    }

    // helper method to find the mailbox of this player, or null if there is no such player
    private ClientLink findLink(String playerId) {
        for (ClientLink link : links) {
            if (link.playerId.equals(playerId)) {
                return link;
            }
        }
        return null;
    }

    // tell one player that their request was refused, and reason 
    private void reject(String playerId, String reason) {
        ClientLink link = findLink(playerId);
        if (link != null) {
            link.send(c -> c.rejected(reason));
        }

    }

    // helper method when time is up to freeze the result and tell everyone
    private void endRound() {
        phase = Phase.OVER;
        roundClock.stop();
        lastSentSeconds = 0;
        PlayerDTO[] standings = currentPlayers();

        // find the highest square count
        int best = 0;
        for (PlayerDTO p : standings) {
            if (p.squares > best) {
                best = p.squares;
            }
        }

        // everyone with best count is a winner (more than one = draw)
        List<PlayerDTO> winnerList = new ArrayList<>();
        for (PlayerDTO p : standings) {
            if (p.squares == best) {
                winnerList.add(p);
            }
        }
        PlayerDTO[] winners = winnerList.toArray(new PlayerDTO[0]);
        // step 12: duration and final state, then give the recording to the host
        recording.add("END " + roundClock.elapsedMs());
        for (PlayerDTO p : standings) {
            recording.add("SCORE " + p.id + " " + p.squares);
        }
        String[] lines = recording.toArray(new String[0]);
        ClientLink hostLink = findLink(hostId);
        if (hostLink != null) {
            hostLink.send(c -> c.recording(lines));
        }
        broadcast(c -> c.timer(0, Phase.OVER));
        broadcast(c -> c.roundOver(winners, standings));
    }

    // send one player their own remaining uses
    private void sendAllowances(String playerId) {
        ClientLink link = findLink(playerId);
        if (link == null) {
            return;
        }
        Map<Tool, Integer> copy = new HashMap<>(uses.get(playerId));
        link.send(c -> c.allowances(copy));
    }

    // power-ups back to full for everyone (Bomb is not reset) 
    private void refillPowerUps() {
        for (Map<Tool, Integer> mine : uses.values()) {
            mine.put(Tool.LINE, Config.POWERUP_USES);
            mine.put(Tool.BLOCK, Config.POWERUP_USES);
            mine.put(Tool.WEDGE, Config.POWERUP_USES);
        }
    }

    // check how many squares are scorched right now (for the 30% cap, B6).
    private int countScorched() {
        int count = 0;
        for (int c = 0; c < Config.COLS; c++) {
            for (int r = 0; r < Config.ROWS; r++) {
                if (scorched[c][r]) {
                    count = count + 1;
                }
            }
        }
        return count;
    }

    //// step 6:
    // tell everyone the match is over and reset
    private void closeMatch(String reason) {
        broadcast(c -> c.matchClosed(reason));
        for (ClientLink link : links) {
            link.close();
        }
        // people waiting for approval hear the match is over too
        for (PendingJoin req : pending.values()) {
            req.link.send(c -> c.matchClosed(reason));
            req.link.close();
        }
        pending.clear();
        players.clear();
        links.clear();
        uses.clear();
        lastSeen.clear(); // step 6
        lastTaunt.clear(); // step 9
        for (int c = 0; c < Config.COLS; c++) {
            for (int r = 0; r < Config.ROWS; r++) {
                owner[c][r] = null;
                scorched[c][r] = false;
            }
        }
        phase = Phase.LOBBY;
        hostId = null;
        roundClock.stop();
    }

    // helper method to remove anyone silent for too long 
    private void checkHeartbeats() {
        long now = System.currentTimeMillis();
        // 1st loop: only collect
        List<String> silent = new ArrayList<>();
        for (String id : lastSeen.keySet()) {
            if (now - lastSeen.get(id) > Protocol.SESSION_TIMEOUT_MS) {
                silent.add(id);
            }
        }
        // 2nd loop: to remove
        for (String id : silent) {
            System.out.println("No heartbeat from " + id + ".");
            doLeave(id);
        }
    }

    // check if the name already used 
    private boolean nameTaken(String name) {
        for (PlayerDTO p : players) {
            if (p.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        // names are taken when waiting for approval
        for (PendingJoin req : pending.values()) {
            if (req.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // use the smallest colour index nobody is using
    private int lowestFreeColour() {
        for (int i = 0; i < Protocol.MAX_PLAYERS; i++) {
            boolean taken = false;
            for (PlayerDTO p : players) {
                if (p.colourIndex == i) {
                    taken = true;
                }
            }
            if (!taken) {
                return i;
            }
        }
        return -1; // never happens: rule 3 already stopped the 17th player
    }

    // add a player - the host at once, others after host approval 
    private void admit(String id, String name, ClientLink link) {
        int colourIndex = lowestFreeColour(); // step 6
        boolean host = id.equals(hostId);
        PlayerDTO self = new PlayerDTO(id, name, colourIndex, host, 0);
        players.add(self);
        recordPlayer(self); // step 12

        // step 5-2
        Map<Tool, Integer> mine = new HashMap<>();
        mine.put(Tool.LINE, Config.POWERUP_USES);
        mine.put(Tool.BLOCK, Config.POWERUP_USES);
        mine.put(Tool.WEDGE, Config.POWERUP_USES);
        mine.put(Tool.BOMB, Config.BOMB_USES);
        uses.put(id, mine);
        lastSeen.put(id, System.currentTimeMillis()); // step 6
        links.add(link); // from now on, broadcasts reach this player too
        System.out.println(name + " joined as " + id);
        link.send(c -> c.joined(self));
        int remaining = 0; // round not started for late joiner
        if (phase != Phase.LOBBY) { // check roundClock when Running / Paused / Over
            remaining = roundClock.remainingSeconds();
        }
        int secs = remaining;
        Phase p = phase;
        link.send(c -> c.timer(secs, p));
        sendAllowances(id); // step 5-2
        PlayerDTO[] everyPlayer = currentPlayers();
        broadcast(c -> c.playersChanged(everyPlayer));
        // step 6-2: the joiner's arena starts all neutral -> send every non-neutral square 
        SquareChange[] arena = fullArena();
        link.send(c -> c.squaresChanged(arena));
        broadcast(c -> c.info(name + " joined."));

    }

    // step 6-2: every square that is not neutral, so a late joiner can catch up
    private SquareChange[] fullArena() {
        List<SquareChange> all = new ArrayList<>();
        for (int c = 0; c < Config.COLS; c++) {
            for (int r = 0; r < Config.ROWS; r++) {
                if (scorched[c][r]) {
                    all.add(new SquareChange(c, r, null, true));
                } else if (owner[c][r] != null) {
                    all.add(new SquareChange(c, r, owner[c][r]));
                }
            }
        }
        return all.toArray(new SquareChange[0]);
    }

    //// step 12:
    // add one event with its time on the round's own clock (pauses excluded)
    private void record(String type, String rest) {
        // only while a round is going
        if (phase != Phase.RUNNING && phase != Phase.PAUSED) {
            return;
        }
        recording.add(type + " " + roundClock.elapsedMs() + " " + rest);
    }

    // one line per changed square
    private void recordSquares(SquareChange[] changes) {
        for (SquareChange c : changes) {
            String state;
            if (c.scorched) {
                state = "#";
            } else if (c.ownerId == null) {
                state = "-";
            } else {
                state = c.ownerId;
            }
            record("SQUARE", c.col + " " + c.row + " " + state);
        }
    }

    // recorda player and their colour
    private void recordPlayer(PlayerDTO p) {
        record("PLAYER", p.id + " " + p.colourIndex + " " + p.host + " " + p.name);
    }
}
