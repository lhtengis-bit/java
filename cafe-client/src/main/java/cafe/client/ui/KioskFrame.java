package cafe.client.ui;

import cafe.client.config.KioskConfig;
import cafe.client.net.ServerConnection;
import cafe.shared.Protocol;

import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Full-screen kiosk when LOCKED; shrinks to a small bottom-right corner
 * widget when UNLOCKED so the user can access the desktop.
 *
 * LOCKED   → full-screen dark overlay, "Please pay at the cashier"
 * UNLOCKED → 270×108 always-on-top widget in the bottom-right corner
 *            showing a color-coded countdown + progress bar
 *
 * Countdown color: green (>5 min) → yellow (1–5 min) → flashing red (<1 min).
 */
public final class KioskFrame extends JFrame implements ServerConnection.Listener {

    private static final String CARD_LOCKED = "LOCKED";
    private static final String CARD_WIDGET = "WIDGET";

    private static final int WARN_YELLOW_SECS = 300;
    private static final int WARN_RED_SECS    = 60;

    private static final int WIDGET_W      = 270;
    private static final int WIDGET_H      = 108;
    private static final int WIDGET_MARGIN = 16;

    private static final Color COLOR_GREEN  = new Color(46, 204, 113);
    private static final Color COLOR_YELLOW = new Color(241, 196, 15);
    private static final Color COLOR_RED    = new Color(231, 76, 60);
    private static final Color COLOR_DIM    = new Color(120, 130, 150);
    private static final Color COLOR_LIGHT  = new Color(220, 230, 245);

    private static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final CardLayout    cards = new CardLayout();
    private final JPanel        root  = new JPanel(cards);

    // Widget card
    private final JLabel        widgetCountdown = new JLabel("00:00:00", SwingConstants.CENTER);
    private final JProgressBar  widgetBar       = new JProgressBar(0, 100);

    // Locked card
    private final JLabel        clockLabel = new JLabel("", SwingConstants.CENTER);

    private final Timer  secondTimer;
    private final Timer  clockTimer;
    private boolean      flashState = false;

    private int secondsRemaining = 0;
    private int totalSeconds     = 1;

    private final KioskConfig  cfg;
    private       ServerConnection conn;
    private final boolean      supportsOpacity;

    public KioskFrame(KioskConfig cfg) {
        super("PC Cafe Kiosk");
        this.cfg = cfg;
        supportsOpacity = checkOpacitySupport();

        setUndecorated(true);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH);

        root.add(buildLockedCard(), CARD_LOCKED);
        root.add(buildWidgetCard(), CARD_WIDGET);
        setContentPane(root);

        secondTimer = new Timer(1000, e -> tick());
        secondTimer.setCoalesce(true);

        clockTimer = new Timer(1000, e -> updateClock());
        clockTimer.setCoalesce(true);
        clockTimer.start();
        updateClock();

        cards.show(root, CARD_LOCKED);
    }

    // ---- Card builders ------------------------------------------------------

    private JPanel buildLockedCard() {
        JPanel p = gradientPanel(new Color(12, 12, 22), new Color(28, 32, 55));
        p.setLayout(new GridBagLayout());

        JLabel badge = new JLabel("LOCKED", SwingConstants.CENTER);
        badge.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        badge.setForeground(new Color(200, 70, 70));
        badge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(140, 50, 50), 2),
                BorderFactory.createEmptyBorder(8, 32, 8, 32)));

        JLabel title = new JLabel("Please pay at the cashier", SwingConstants.CENTER);
        title.setForeground(COLOR_LIGHT);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 64));

        JLabel stationLbl = new JLabel("Station #" + cfg.stationId(), SwingConstants.CENTER);
        stationLbl.setForeground(COLOR_DIM);
        stationLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 30));

        JPanel divider = new JPanel();
        divider.setPreferredSize(new Dimension(600, 2));
        divider.setBackground(new Color(60, 70, 100));
        divider.setOpaque(true);

        clockLabel.setForeground(new Color(150, 165, 200));
        clockLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 34));

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.anchor = GridBagConstraints.CENTER;

        c.gridy = 0; c.fill = GridBagConstraints.NONE;       c.insets = new Insets(0, 20, 36, 20); p.add(badge,      c);
        c.gridy = 1;                                          c.insets = new Insets(0, 20, 16, 20); p.add(title,      c);
        c.gridy = 2;                                          c.insets = new Insets(0, 20, 36, 20); p.add(stationLbl, c);
        c.gridy = 3; c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 20, 36, 20); p.add(divider,    c);
        c.gridy = 4; c.fill = GridBagConstraints.NONE;       c.insets = new Insets(0, 20, 8,  20); p.add(clockLabel, c);

        return p;
    }

    private JPanel buildWidgetCard() {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setBackground(new Color(15, 20, 35));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(55, 70, 110), 1),
                BorderFactory.createEmptyBorder(8, 14, 8, 14)));

        JLabel stationLbl = new JLabel("Station #" + cfg.stationId(), SwingConstants.LEFT);
        stationLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        stationLbl.setForeground(COLOR_DIM);

        JLabel timeLbl = new JLabel("time remaining", SwingConstants.RIGHT);
        timeLbl.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        timeLbl.setForeground(COLOR_DIM);

        JPanel topRow = new JPanel(new BorderLayout());
        topRow.setOpaque(false);
        topRow.add(stationLbl, BorderLayout.WEST);
        topRow.add(timeLbl,    BorderLayout.EAST);

        widgetCountdown.setFont(new Font(Font.MONOSPACED, Font.BOLD, 42));
        widgetCountdown.setForeground(COLOR_GREEN);

        widgetBar.setBorderPainted(false);
        widgetBar.setStringPainted(false);
        widgetBar.setForeground(COLOR_GREEN);
        widgetBar.setBackground(new Color(40, 52, 75));
        widgetBar.setPreferredSize(new Dimension(0, 5));
        widgetBar.setValue(100);

        p.add(topRow,          BorderLayout.NORTH);
        p.add(widgetCountdown, BorderLayout.CENTER);
        p.add(widgetBar,       BorderLayout.SOUTH);
        return p;
    }

    private JPanel gradientPanel(Color top, Color bottom) {
        return new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2.setPaint(new GradientPaint(0, 0, top, 0, getHeight(), bottom));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
    }

    // ---- Window state management -------------------------------------------

    private void switchToWidget() {
        if (supportsOpacity) setOpacity(0.88f);
        setAlwaysOnTop(true);
        setExtendedState(JFrame.NORMAL);
        Rectangle sb = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(WIDGET_W, WIDGET_H);
        setLocation(sb.x + sb.width  - WIDGET_W - WIDGET_MARGIN,
                    sb.y + sb.height - WIDGET_H - WIDGET_MARGIN);
        cards.show(root, CARD_WIDGET);
    }

    private void switchToFullScreen() {
        if (supportsOpacity) setOpacity(1.0f);
        setAlwaysOnTop(false);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        cards.show(root, CARD_LOCKED);
    }

    // ---- Wiring ------------------------------------------------------------

    public void attach(ServerConnection conn) {
        this.conn = conn;
    }

    // ---- ServerConnection.Listener -----------------------------------------

    @Override
    public void onUnlock(int seconds) {
        secondsRemaining = Math.max(0, seconds);
        totalSeconds = secondsRemaining > 0 ? secondsRemaining : 1;
        renderCountdown();
        switchToWidget();
        if (!secondTimer.isRunning()) secondTimer.start();
    }

    @Override
    public void onLock() {
        secondsRemaining = 0;
        totalSeconds = 1;
        renderCountdown();
        secondTimer.stop();
        switchToFullScreen();
    }

    // ---- Timer callbacks ---------------------------------------------------

    private void tick() {
        if (secondsRemaining <= 0) {
            secondTimer.stop();
            switchToFullScreen();
            if (conn != null) conn.send(Protocol.CMD_TIME_EXPIRED);
            return;
        }
        secondsRemaining--;
        renderCountdown();
    }

    private void updateClock() {
        clockLabel.setText(LocalTime.now().format(HH_MM_SS));
    }

    // ---- Render helpers ----------------------------------------------------

    private void renderCountdown() {
        int h = secondsRemaining / 3600;
        int m = (secondsRemaining % 3600) / 60;
        int s = secondsRemaining % 60;
        widgetCountdown.setText(String.format("%02d:%02d:%02d", h, m, s));

        Color color;
        if (secondsRemaining <= WARN_RED_SECS) {
            flashState = !flashState;
            color = flashState ? COLOR_RED : new Color(160, 35, 25);
        } else if (secondsRemaining <= WARN_YELLOW_SECS) {
            color = COLOR_YELLOW;
        } else {
            color = COLOR_GREEN;
        }
        widgetCountdown.setForeground(color);
        widgetBar.setForeground(color);
        widgetBar.setValue(Math.max(0, Math.min(100, (int) (100L * secondsRemaining / totalSeconds))));
    }

    // ---- Helpers -----------------------------------------------------------

    private static boolean checkOpacitySupport() {
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            return gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT);
        } catch (Exception e) {
            return false;
        }
    }
}
