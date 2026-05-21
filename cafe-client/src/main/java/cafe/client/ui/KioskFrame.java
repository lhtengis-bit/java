package cafe.client.ui;

import cafe.client.config.KioskConfig;
import cafe.client.net.ServerConnection;
import cafe.shared.Protocol;

import javax.swing.*;
import java.awt.*;

/**
 * Locked-down full-screen kiosk window. Two cards:
 *   1. LOCKED   — "Station #101 / Please pay cashier"
 *   2. UNLOCKED — large HH:MM:SS countdown driven by a javax.swing.Timer
 *
 * <p>The window is undecorated so the user cannot move/close it. The
 * kiosk has no DB access and no authority to alter its own time — it
 * strictly obeys commands from the server.
 */
public final class KioskFrame extends JFrame implements ServerConnection.Listener {

    private static final String CARD_LOCKED   = "LOCKED";
    private static final String CARD_UNLOCKED = "UNLOCKED";

    // Colour thresholds for countdown urgency
    private static final int WARN_AMBER_SECS = 300;  // 5 min
    private static final int WARN_RED_SECS   = 60;   // 1 min

    private final CardLayout cards = new CardLayout();
    private final JPanel  root  = new JPanel(cards);
    private final JLabel  countdown = new JLabel("00:00:00", SwingConstants.CENTER);
    private final JPanel  unlockedPanel; // stored for background colour updates

    private final Timer secondTimer;
    private int secondsRemaining = 0;
    private boolean beepedAt60 = false;

    private final KioskConfig cfg;
    private ServerConnection conn;

    public KioskFrame(KioskConfig cfg) {
        super("PC Cafe Kiosk");
        this.cfg = cfg;

        setUndecorated(true);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH);

        root.add(buildLockedCard(), CARD_LOCKED);
        this.unlockedPanel = buildUnlockedCard();
        root.add(unlockedPanel, CARD_UNLOCKED);
        setContentPane(root);

        // Tick once per second; updates the countdown label and urgency colours.
        secondTimer = new Timer(1000, e -> tick());
        secondTimer.setCoalesce(true);

        cards.show(root, CARD_LOCKED);
    }

    // U5: Station # is now the big text; "Please pay cashier" is the subtitle
    private JPanel buildLockedCard() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBackground(new Color(20, 20, 20));

        JLabel stationLbl = new JLabel("Station #" + cfg.stationId(), SwingConstants.CENTER);
        stationLbl.setForeground(Color.WHITE);
        stationLbl.setFont(stationLbl.getFont().deriveFont(Font.BOLD, 72f));

        JLabel sub = new JLabel("Please pay cashier", SwingConstants.CENTER);
        sub.setForeground(new Color(180, 180, 180));
        sub.setFont(sub.getFont().deriveFont(Font.PLAIN, 28f));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.insets = new Insets(8, 8, 8, 8);
        c.gridy = 0; p.add(stationLbl, c);
        c.gridy = 1; p.add(sub, c);
        return p;
    }

    private JPanel buildUnlockedCard() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(new Color(245, 245, 245));

        JLabel header = new JLabel("Session active", SwingConstants.CENTER);
        header.setFont(header.getFont().deriveFont(Font.PLAIN, 36f));
        header.setBorder(BorderFactory.createEmptyBorder(40, 0, 0, 0));

        countdown.setFont(countdown.getFont().deriveFont(Font.BOLD, 160f));

        p.add(header,    BorderLayout.NORTH);
        p.add(countdown, BorderLayout.CENTER);
        return p;
    }

    public void attach(ServerConnection conn) {
        this.conn = conn;
    }

    // ---- ServerConnection.Listener ---------------------------------------

    @Override
    public void onUnlock(int seconds) {
        secondsRemaining = Math.max(0, seconds);
        beepedAt60 = false;
        renderCountdown();
        cards.show(root, CARD_UNLOCKED);
        if (!secondTimer.isRunning()) secondTimer.start();
    }

    @Override
    public void onLock() {
        secondsRemaining = 0;
        renderCountdown();
        secondTimer.stop();
        cards.show(root, CARD_LOCKED);
    }

    private void tick() {
        if (secondsRemaining <= 0) {
            secondTimer.stop();
            cards.show(root, CARD_LOCKED);
            Toolkit.getDefaultToolkit().beep(); // U2: audio alert on expiry
            if (conn != null) conn.send(Protocol.CMD_TIME_EXPIRED);
            return;
        }
        // U2: 1-minute warning beep
        if (secondsRemaining == WARN_RED_SECS && !beepedAt60) {
            Toolkit.getDefaultToolkit().beep();
            beepedAt60 = true;
        }
        secondsRemaining--;
        renderCountdown();
    }

    // U1: Update background and label colour based on urgency thresholds
    private void renderCountdown() {
        int h = secondsRemaining / 3600;
        int m = (secondsRemaining % 3600) / 60;
        int s = secondsRemaining % 60;
        countdown.setText(String.format("%02d:%02d:%02d", h, m, s));

        if (secondsRemaining > WARN_AMBER_SECS) {
            unlockedPanel.setBackground(new Color(245, 245, 245));
            countdown.setForeground(new Color(30, 30, 30));
        } else if (secondsRemaining > WARN_RED_SECS) {
            unlockedPanel.setBackground(new Color(255, 243, 205)); // amber
            countdown.setForeground(new Color(160, 90, 0));
        } else {
            unlockedPanel.setBackground(new Color(255, 225, 225)); // red tint
            countdown.setForeground(new Color(180, 0, 0));
        }
    }
}
