package turfwar.client;
import turfwar.net.ClientCallback;
import turfwar.net.PlayerDTO;
import turfwar.net.SquareChange;
import turfwar.api.GameView;
import turfwar.model.Phase;
import turfwar.model.PlayerInfo;
import turfwar.model.Square;
import turfwar.model.Tool;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
/**
 * Implementation of the ClientCallback interface, which is called by the server.
 * The server calls these methods on the client, to update the client with new information.
 * The client then updates its view and controller with this information.
 */
public class CallbackImplementation extends UnicastRemoteObject implements ClientCallback {
    private final GameView view;
    private final NetworkController controller;

    
    public CallbackImplementation(GameView view, NetworkController controller) throws RemoteException {
        super();
        this.view = view;
        this.controller = controller;
    }    
    // step 3: convert PlayerDTO to PlayerInfo
    private PlayerInfo toInfo(PlayerDTO d) {
        return new PlayerInfo(d.id, d.name, d.colourIndex, d.host, d.squares);
    }
    // step 1: show info message
    @Override 
    public void info(String message) throws RemoteException{
        view.showInfo(message);
    }
    // step 2
    // the client was admitted: store its id (so pings can start) and show it as the local player
    @Override 
    public void joined(PlayerDTO self) throws RemoteException {
        controller.setMyId(self.id);
        view.setLocalPlayer(toInfo(self));
    }
    // notify the client when the list of players has changed
    @Override 
    public void playersChanged(PlayerDTO[] players) throws RemoteException {
        List<PlayerInfo> playersList = new ArrayList<>();
        for (PlayerDTO p : players) {
            playersList.add(toInfo(p));
        }
        controller.livePlayers(playersList); // step 12
    }

    // step 4: notify the client when the state of squares has changed
    @Override 
    public void squaresChanged(SquareChange[] changes) throws RemoteException {
        for (SquareChange c : changes) {
            Square square;
            if (c.scorched) { // step 5-3
                square = Square.SCORCHED;
            } else if (c.ownerId == null) {
                square = Square.NEUTRAL;
            } else {
                square = Square.ownedBy(c.ownerId);
            }
            controller.liveSquare(c.col, c.row, square); // step 12
        }
    }

    // step 5
    // notify the client of the current timer and phase
    @Override 
    public void timer(int remainingSeconds, Phase phase) throws RemoteException {
        controller.liveTimer(remainingSeconds, phase); // step 12
    }
    // notify the client when their request was refused (paint while paused, kick by a non-host)
    @Override 
    public void rejected(String reason) throws RemoteException {
        view.showError(reason);
    }
    // notify the client when the round is over, who wins and the final standings
    @Override 
    public void roundOver(PlayerDTO[] winners, PlayerDTO[] standings) throws RemoteException {
        List<PlayerInfo> winnerlList = new ArrayList<>();
        for (PlayerDTO p : winners) {
            winnerlList.add(toInfo(p));
        }
        List<PlayerInfo> standingList = new ArrayList<>();
        for (PlayerDTO p : standings) {
            standingList.add(toInfo(p));
        }
        view.showRoundOver(winnerlList, standingList);
    }
    // notify the client of the current allowances for each tool
    @Override 
    public void allowances(Map<Tool, Integer> remaining) throws RemoteException {
        view.setAllowances(remaining);
    }
    
    // step 6
    // notify the client when the match ended for this client (host left, kicked, denied join)
    @Override 
    public void matchClosed(String reason) throws RemoteException {
        controller.stopReplay(); // step 12: playback stops, then closed normally
        view.showMatchClosed(reason);
    }
    // host only - show the non-blocking Approve / Deny dialog 
    @Override
    public void joinRequest(String requestId, String name) throws RemoteException {
        view.showJoinRequest(requestId, name);
    } 

    // step 9: notify the client when a player has sent a taunt
    @Override
    public void taunt(PlayerDTO from, String text) throws RemoteException {
        view.showTaunt(toInfo(from), text);
    }
    
    // step 12: host only - store the finished round’s replay log for Replay and Save Replay
    @Override
    public void recording(String[] lines) throws RemoteException {
        controller.setLastRecording(lines);
    }
}
