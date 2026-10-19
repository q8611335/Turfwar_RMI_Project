package turfwar;

import turfwar.client.NetworkController;
import turfwar.demo.DemoController;
import turfwar.net.Protocol;
import turfwar.ui.MainWindow;
import turfwar.ui.StartDialog;


import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Entry point of the client jar. Usage: java -jar TurfWarClient.jar [serverAddress] [serverPort] [username]
 * Command-line values only pre-fill the start dialog.
 */
public final class Main {
    public static void main(String[] args) {
        Protocol.applyJvmDefaults(); // step 7: before any RMI is used
        final String address = args.length > 0 ? args[0] : null;
        final int port = args.length > 1 ? parsePort(args[1]) : -1;
        final String name = args.length > 2 ? args[2] : null;

        SwingUtilities.invokeLater(() -> {
            StartDialog.Options opt = new StartDialog(address, port, name).showAndGet();
            if (opt == null) System.exit(0);

            MainWindow window = new MainWindow();

            switch (opt.mode) {
                case LOCAL_DEMO:
                    window.setController(new DemoController(window, opt.username, opt.roundSeconds));
                    break;
                case HOST: 
                    // first user creates the match: chooses the round length, becomes the host and gets colour 0 
                    // host-only actions (start round, approve or deny joins, pause, kick) are checked by the server
                    openNetworkGame(window, opt, true);
                    break;
                case JOIN:
                    // other client asks to join
                    // the host approves or denies the request 
                    openNetworkGame(window, opt, false);
                    break;
                    
                default:
                    throw new IllegalStateException();
            }
            window.setVisible(true);
        });
    }

    // step 3: open a network game, either hosting or joining, and set the controller to the window
    private static void openNetworkGame(MainWindow window, StartDialog.Options opt, boolean hosting) {
        NetworkController controller = new NetworkController(window);
        window.setController(controller);
        controller.connect(window, opt, hosting);
    }
    // step 7: called from the outbox thread when connecting failed or the server refused request
    // show reason and opens the start dialog again 
    public static void backToStart(MainWindow window, StartDialog.Options opt, String reason) {
        SwingUtilities.invokeLater(() -> { // UI work must happen on the Swing thread
            window.dispose(); // close the window that never got in
            JOptionPane.showMessageDialog(null, reason, "Turf War", JOptionPane.WARNING_MESSAGE);

            // ask again, with the user's values kept
            StartDialog.Options again = new StartDialog(opt.serverAddress, opt.port, opt.username).showAndGet();
            if (again == null) {
                System.exit(0);   // user pressed Quit
            }
            MainWindow newWindow = new MainWindow();
            openNetworkGame(newWindow, again, again.mode == StartDialog.Mode.HOST);
            newWindow.setVisible(true); 
        });
    }
    // parse the port number, or return -1 if invalid 
    private static int parsePort(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }
}
