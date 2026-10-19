package turfwar.net;

import turfwar.model.Phase;
import turfwar.model.Tool;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.Map;

/**
 * Interface shared between clients and server (server -> client)
 */
public interface ClientCallback extends Remote {

    // step 1: send information messages to the client
    public void info(String message) throws RemoteException;
    // step 2
    // notify the client when they have joined the game
    public void joined(PlayerDTO self) throws RemoteException;
    // notify the client when the list of players has changed
    public void playersChanged(PlayerDTO[] players) throws RemoteException;

    // step 4: notify the client when the state of squares has changed
    public void squaresChanged(SquareChange[] changes) throws RemoteException;

    // step 5
    // notify the client of the current timer and phase
    public void timer(int remainingSeconds, Phase phase) throws RemoteException;
    // notify the client when their request was refused (paint while paused, kick by a non-host)
    public void rejected(String reason) throws RemoteException;
    // notify the client when the round is over, who wins and the final standings
    public void roundOver(PlayerDTO[] winners, PlayerDTO[] standings) throws RemoteException;
    // notify the client of the current allowances for each tool
    public void allowances(Map<Tool, Integer> remaining) throws RemoteException;

    // step 6
    // notify the client when the match ended for this client (host left, kicked, denied join)
    public void matchClosed(String reason) throws RemoteException;
    // notify the client(host) when a player has requested to join the match
    public void joinRequest(String requestId, String name) throws RemoteException;

    // step 9: notify the client when a player has sent a taunt
    public void taunt(PlayerDTO from, String text) throws RemoteException;

    // step 12: the finished round’s replay log, sent to the host only when a round ends
    public void recording(String[] lines) throws RemoteException;
}
