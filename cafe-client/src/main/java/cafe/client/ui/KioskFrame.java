package cafe.client.ui;

import cafe.client.config.KioskConfig;
import cafe.client.net.ServerConnection;
import cafe.shared.Protocol;

import javax.swing.*;
import java.awt.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Full-screen kiosk when LOCKED; collapses to a 10%-wide right-side sidebar
 * when UNLOCKED so the user can access the desktop.
 *
 * Sidebar layout (top → bottom):
 *   ┌──────────┐
 *   │ STATION  │  station header
 *   ├──────────┤
 *   │          │
 *   │ РЕКЛАМ   │  advertisement panel (fills most of the space)
 *   │          │
 *   ├──────────┤
 *   │ 14:35:22 │  current clock
 *   │  2 цаг   │  remaining time  } bottom info panel
 *   │ 30 мин   │
 *   │ үлдсэн   │
 *   │ Нийт 2ц  │  payment info
 *   └──────────┘
 */
public final class KioskFrame extends JFrame implements ServerConnection.Listener {

    private static final String CARD_LOCKED  = "LOCKED";
    private static final String CARD_SIDEBAR = "SIDEBAR";

    private static final int WARN_YELLOW_SECS = 300;
    private static final int WARN_RED_SECS    = 60;

    private static final Color COLOR_GREEN   = new Color(46, 204, 113);
    private static final Color COLOR_YELLOW  = new Color(241, 196, 15);
    private static final Color COLOR_RED     = new Color(231, 76, 60);
    private static final Color COLOR_DIM     = new Color(110, 120, 145);
    private static final Color COLOR_LIGHT   = new Color(220, 230, 245);
    private static final Color COLOR_BG      = new Color(13, 17, 30);
    private static final Color COLOR_BG2     = new Color(18, 24, 42);
    private static final Color COLOR_ACCENT  = new Color(88, 130, 255);

    private static final DateTimeFormatter HH_MM_SS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final CardLayout cards = new CardLayout();
    private final JPanel     root  = new JPanel(cards);

    // Sidebar — time remaining
    private final JLabel sideHours   = new JLabel("0",  SwingConstants.CENTER);
    private final JLabel sideMins    = new JLabel("00", SwingConstants.CENTER);
    private final JLabel sideSecs    = new JLabel("00", SwingConstants.CENTER);

    // Sidebar — payment info
    private final JLabel totalLabel  = new JLabel("",  SwingConstants.CENTER);

    // Sidebar — progress bar (horizontal, at top of info panel)
    private final JProgressBar sideBar = new JProgressBar(0, 100);

    // Locked card / clock (also used in sidebar bottom)
    private final JLabel clockLabel  = new JLabel("", SwingConstants.CENTER);
    private final JLabel sideClockLabel = new JLabel("", SwingConstants.CENTER);

    private final Timer  secondTimer;
    private final Timer  clockTimer;
    private boolean      flashState = false;

    private int secondsRemaining = 0;
    private int totalSeconds     = 1;

    private final KioskConfig  cfg;
    private ServerConnection   conn;
    private final boolean      supportsOpacity;

    public KioskFrame(KioskConfig cfg) {
        super("PC Cafe Kiosk");
        this.cfg = cfg;
        supportsOpacity = checkOpacitySupport();

        setUndecorated(true);
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setExtendedState(JFrame.MAXIMIZED_BOTH);

        root.add(buildLockedCard(),  CARD_LOCKED);
        root.add(buildSidebarCard(), CARD_SIDEBAR);
        setContentPane(root);

        secondTimer = new Timer(1000, e -> tick());
        secondTimer.setCoalesce(true);

        clockTimer = new Timer(1000, e -> updateClocks());
        clockTimer.setCoalesce(true);
        clockTimer.start();
        updateClocks();

        cards.show(root, CARD_LOCKED);
    }

    // =========================================================================
    // Card builders
    // =========================================================================

    // ---- Locked (full-screen) card ------------------------------------------

    private JPanel buildLockedCard() {
        JPanel p = gradientPanel(new Color(12, 12, 22), new Color(28, 32, 55));
        p.setLayout(new GridBagLayout());

        JLabel badge = new JLabel("LOCKED", SwingConstants.CENTER);
        badge.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 22));
        badge.setForeground(new Color(200, 70, 70));
        badge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(140, 50, 50), 2),
                BorderFactory.createEmptyBorder(8, 32, 8, 32)));

        JLabel title = new JLabel("Та төлбөрөө төлнө үү", SwingConstants.CENTER);
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

    // ---- Sidebar card -------------------------------------------------------

    private JPanel buildSidebarCard() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(COLOR_BG);
        p.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, new Color(50, 68, 115)));

        p.add(buildStationHeader(), BorderLayout.NORTH);
        p.add(buildAdPanel(),       BorderLayout.CENTER);
        p.add(buildInfoPanel(),     BorderLayout.SOUTH);
        return p;
    }

    /** Top strip: station ID */
    private JPanel buildStationHeader() {
        JPanel p = new JPanel(new GridLayout(2, 1, 0, 2));
        p.setBackground(new Color(20, 26, 48));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(45, 60, 100)),
                BorderFactory.createEmptyBorder(10, 8, 10, 8)));

        JLabel word = label("STATION", 10, Font.BOLD, COLOR_DIM);
        JLabel num  = label("#" + cfg.stationId(), 18, Font.BOLD, COLOR_LIGHT);
        p.add(word); p.add(num);
        return p;
    }

    /** Center: advertisement / promo content */
    private JPanel buildAdPanel() {
        JPanel p = gradientPanel(new Color(15, 22, 50), new Color(10, 15, 36));
        p.setLayout(new GridBagLayout());

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(6, 10, 6, 10);

        // Cafe name / brand
        JLabel brand = label("NMIT INTERNET CAFE", 16, Font.BOLD, COLOR_ACCENT);
        JLabel slogan = label("Тавтай морил!", 12, Font.PLAIN, COLOR_LIGHT);

        JSeparator sep1 = separator();

        // Features
        JLabel feat1 = label("100 Mbps интернет", 12, Font.PLAIN, COLOR_DIM);
        JLabel feat2 = label("Тоглоомын компьютер", 12, Font.PLAIN, COLOR_DIM);
        JLabel feat3 = label("HD дэлгэц", 12, Font.PLAIN, COLOR_DIM);

        JSeparator sep2 = separator();

        // Pricing
        JLabel priceTitle = label("ТАРИФ", 10, Font.BOLD, COLOR_DIM);
        JLabel price      = label("1 мин = 100₮", 15, Font.BOLD, COLOR_GREEN);
        JLabel priceNote  = label("(6,000₮/цаг)", 11, Font.PLAIN, COLOR_DIM);

        int row = 0;
        c.gridy = row++; c.insets = new Insets(16, 10, 2, 10);  p.add(brand,      c);
        c.gridy = row++; c.insets = new Insets(2,  10, 12, 10); p.add(slogan,     c);
        c.gridy = row++; c.insets = new Insets(0,  6,  8,  6);  p.add(sep1,       c);
        c.gridy = row++; c.insets = new Insets(4,  10, 3, 10);  p.add(feat1,      c);
        c.gridy = row++; c.insets = new Insets(3,  10, 3, 10);  p.add(feat2,      c);
        c.gridy = row++; c.insets = new Insets(3,  10, 8, 10);  p.add(feat3,      c);
        c.gridy = row++; c.insets = new Insets(0,  6,  8,  6);  p.add(sep2,       c);
        c.gridy = row++; c.insets = new Insets(4,  10, 2, 10);  p.add(priceTitle, c);
        c.gridy = row++; c.insets = new Insets(2,  10, 2, 10);  p.add(price,      c);
        c.gridy = row++;                                          p.add(priceNote,  c);

        return p;
    }

    /** Bottom: clock + remaining time + payment summary */
    private JPanel buildInfoPanel() {
        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(new Color(10, 14, 26));
        outer.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(45, 60, 100)));

        // Thin progress bar at very top of info panel
        sideBar.setBorderPainted(false);
        sideBar.setStringPainted(false);
        sideBar.setForeground(COLOR_GREEN);
        sideBar.setBackground(new Color(28, 36, 58));
        sideBar.setPreferredSize(new Dimension(0, 5));
        sideBar.setValue(100);

        // Clock
        sideClockLabel.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
        sideClockLabel.setForeground(new Color(140, 155, 195));
        sideClockLabel.setBorder(BorderFactory.createEmptyBorder(10, 8, 4, 8));

        JSeparator sepA = separator();

        // Time remaining — horizontal row: [ цаг ] [ мин ] [ сек ]
        sideHours.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        sideHours.setForeground(COLOR_GREEN);
        sideMins.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        sideMins.setForeground(COLOR_GREEN);
        sideSecs.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        sideSecs.setForeground(COLOR_GREEN);

        // Numbers row
        JPanel numRow = new JPanel(new GridLayout(1, 3, 4, 0));
        numRow.setOpaque(false);
        numRow.add(sideHours);
        numRow.add(sideMins);
        numRow.add(sideSecs);

        // Labels row
        JPanel wordRow = new JPanel(new GridLayout(1, 3, 4, 0));
        wordRow.setOpaque(false);
        wordRow.add(label("цаг", 11, Font.PLAIN, COLOR_DIM));
        wordRow.add(label("мин", 11, Font.PLAIN, COLOR_DIM));
        wordRow.add(label("сек", 11, Font.PLAIN, COLOR_DIM));

        JPanel timeBlock = new JPanel(new BorderLayout(0, 2));
        timeBlock.setOpaque(false);
        timeBlock.setBorder(BorderFactory.createEmptyBorder(4, 8, 2, 8));
        timeBlock.add(numRow,  BorderLayout.CENTER);
        timeBlock.add(wordRow, BorderLayout.SOUTH);

        JLabel uldsen = label("үлдсэн", 11, Font.PLAIN, COLOR_DIM);

        JSeparator sepB = separator();

        // Payment / session info
        JLabel payTitle = label("ТӨЛБӨРИЙН МЭДЭЭЛЭЛ", 9, Font.BOLD, COLOR_DIM);
        totalLabel.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        totalLabel.setForeground(COLOR_DIM);
        totalLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 14, 8));

        JPanel inner = new JPanel();
        inner.setOpaque(false);
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.add(sideClockLabel);
        inner.add(wrap(sepA, 6));
        inner.add(timeBlock);
        inner.add(uldsen);
        inner.add(wrap(sepB, 6));
        inner.add(payTitle);
        inner.add(totalLabel);

        outer.add(sideBar, BorderLayout.NORTH);
        outer.add(inner,   BorderLayout.CENTER);
        return outer;
    }

    // =========================================================================
    // Window state
    // =========================================================================

    private void switchToSidebar() {
        if (supportsOpacity) setOpacity(0.93f);
        setAlwaysOnTop(true);
        setExtendedState(JFrame.NORMAL);
        Rectangle sb = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int sideW = Math.max(130, sb.width * 15 / 100); // 15% of screen width
        setSize(sideW, sb.height);
        setLocation(sb.x + sb.width - sideW, sb.y);
        cards.show(root, CARD_SIDEBAR);
    }

    private void switchToFullScreen() {
        if (supportsOpacity) setOpacity(1.0f);
        setAlwaysOnTop(false);
        setExtendedState(JFrame.MAXIMIZED_BOTH);
        cards.show(root, CARD_LOCKED);
    }

    // =========================================================================
    // Wiring
    // =========================================================================

    public void attach(ServerConnection conn) {
        this.conn = conn;
    }

    // =========================================================================
    // ServerConnection.Listener
    // =========================================================================

    @Override
    public void onUnlock(int seconds) {
        secondsRemaining = Math.max(0, seconds);
        totalSeconds = secondsRemaining > 0 ? secondsRemaining : 1;
        renderTime();
        switchToSidebar();
        if (!secondTimer.isRunning()) secondTimer.start();
    }

    @Override
    public void onLock() {
        secondsRemaining = 0;
        totalSeconds = 1;
        renderTime();
        secondTimer.stop();
        switchToFullScreen();
    }

    // =========================================================================
    // Timer callbacks
    // =========================================================================

    private void tick() {
        if (secondsRemaining <= 0) {
            secondTimer.stop();
            switchToFullScreen();
            if (conn != null) conn.send(Protocol.CMD_TIME_EXPIRED);
            return;
        }
        secondsRemaining--;
        renderTime();
    }

    private void updateClocks() {
        String t = LocalTime.now().format(HH_MM_SS);
        clockLabel.setText(t);
        sideClockLabel.setText(t);
    }

    // =========================================================================
    // Render
    // =========================================================================

    private void renderTime() {
        int h = secondsRemaining / 3600;
        int m = (secondsRemaining % 3600) / 60;
        int s = secondsRemaining % 60;

        sideHours.setText(String.valueOf(h));
        sideMins.setText(String.format("%02d", m));
        sideSecs.setText(String.format("%02d", s));

        // Payment info: show session total vs remaining
        int th = totalSeconds / 3600;
        int tm = (totalSeconds % 3600) / 60;
        totalLabel.setText(String.format(
                "<html><center>Нийт: %dц %02dм<br>Үлдсэн: %dц %02dм</center></html>",
                th, tm, h, m));

        // Color based on urgency
        Color color;
        if (secondsRemaining <= WARN_RED_SECS) {
            flashState = !flashState;
            color = flashState ? COLOR_RED : new Color(160, 35, 25);
        } else if (secondsRemaining <= WARN_YELLOW_SECS) {
            color = COLOR_YELLOW;
        } else {
            color = COLOR_GREEN;
        }

        sideHours.setForeground(color);
        sideMins.setForeground(color);
        sideSecs.setForeground(color);
        sideBar.setForeground(color);
        sideBar.setValue(Math.max(0, Math.min(100, (int) (100L * secondsRemaining / totalSeconds))));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static JLabel label(String text, int size, int style, Color fg) {
        JLabel l = new JLabel(text, SwingConstants.CENTER);
        l.setFont(new Font(Font.SANS_SERIF, style, size));
        l.setForeground(fg);
        return l;
    }

    private static JSeparator separator() {
        JSeparator s = new JSeparator();
        s.setForeground(new Color(45, 60, 95));
        return s;
    }

    /** Wraps a component with vertical padding in a transparent panel */
    private static JPanel wrap(JComponent comp, int vPad) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(BorderFactory.createEmptyBorder(vPad, 6, vPad, 6));
        p.add(comp);
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

    private static boolean checkOpacitySupport() {
        try {
            GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            return gd.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT);
        } catch (Exception e) {
            return false;
        }
    }
}
