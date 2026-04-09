package com.runewrapped;

// Imports
import net.runelite.api.Skill;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class ClanTrackerPanel extends PluginPanel {
	// ============================================================
	// Dependencies
	// ============================================================

	private final ClanTrackerStore store;
	private final SkillIconManager skillIconManager;

	// ============================================================
	// Selection state
	// ============================================================

	/**
	 * null == ALL skills
	 */
	private Skill selectedSkill = null;

	/**
	 * Rolling range in days. Uses:
	 * - 0 => Session
	 * - 1 => 1d
	 * - 7 => 7d
	 * - 30 => 30d
	 */
	private int selectedDays = 7;

	/**
	 * Reserved for later boss work; retained for compatibility with existing calls.
	 */
	private String activeBossKey = "Vorkath";

	// ============================================================
	// UI - Header
	// ============================================================

	private final JLabel header = new JLabel("ClanTracker (Alpha)");
	private final JLabel subtitle = new JLabel("Skills XP recap");

	// ============================================================
	// UI - Range toggles
	// ============================================================

	private final JToggleButton btnSession = makeChip("Session");
	private final JToggleButton btn1d = makeChip("1d");
	private final JToggleButton btn7d = makeChip("7d");
	private final JToggleButton btn30d = makeChip("30d");

	// ============================================================
	// UI - Summary card
	// ============================================================

	private final JLabel cardTitle = new JLabel("Total XP");
	private final JLabel cardValue = new JLabel("0");
	private final JLabel cardSub = new JLabel("Last 7 days");

	// ============================================================
	// UI - Detail area
	// ============================================================

	private final JLabel detailTitle = new JLabel("Top skills");
	private final JLabel detailLine1 = new JLabel("");
	private final JLabel detailLine2 = new JLabel("");
	private final JLabel detailLine3 = new JLabel("");

	private final JLabel[] topSkillLines = new JLabel[5];

	private final JPanel detailBody = new JPanel();
	private final JPanel topListPanel = new JPanel();
	private final JPanel singleSkillPanel = new JPanel();

	// ============================================================
	// UI - Heartbeat status area
	// ============================================================

	private java.util.function.Supplier<HeartbeatStatus> heartbeatStatusSupplier = null;
	private boolean clientLoggedIn = false;

	private final JLabel hbLine1 = new JLabel("Uploads: Off");
	private final JLabel hbLine2 = new JLabel("Pending: -");
	private final JLabel hbLine3 = new JLabel("Last: -");
	private final JLabel hbLine4 = new JLabel("");
	public String readinessHint;

	// ============================================================
	// UI - Actions
	// ============================================================

	private Runnable exportLastSessionHandler = () -> {
	};

	// ============================================================
	// Construction
	// ============================================================

	public ClanTrackerPanel(ClanTrackerStore store, SkillIconManager skillIconManager) {
		super(false);
		this.store = store;
		this.skillIconManager = skillIconManager;

		// ---- Panel base ----
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel root = new JPanel();
		root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(new EmptyBorder(8, 8, 8, 8));

		// ---- Build UI sections ----
		root.add(buildHeader());
		root.add(Box.createVerticalStrut(8));

		root.add(buildTimeframeRow());
		root.add(Box.createVerticalStrut(8));

		root.add(buildSummaryCard());
		root.add(Box.createVerticalStrut(8));

		root.add(buildExportButton());
		root.add(Box.createVerticalStrut(8));

		root.add(buildHeartbeatStatusBlock());
		root.add(Box.createVerticalStrut(8));

		root.add(buildSkillGrid());
		root.add(Box.createVerticalStrut(8));

		root.add(buildDetailBlock());

		add(root, BorderLayout.NORTH);

		// Initial paint
		refresh();
	}

	// ============================================================
	// Public API (called by Plugin)
	// ============================================================

	/**
	 * Reserved for later boss work; retained so plugin can call it safely.
	 */
	public void setActiveBossKey(String bossKey) {
		this.activeBossKey = bossKey;
	}

	public void setExportLastSessionHandler(Runnable r) {
		this.exportLastSessionHandler = (r != null) ? r : () -> {
		};
	}

	public void setHeartbeatStatusSupplier(java.util.function.Supplier<HeartbeatStatus> supplier) {
		this.heartbeatStatusSupplier = supplier;
	}

	// ============================================================
	// Refresh / rendering
	// ============================================================

	public void refresh() {
		// Ensure all UI updates happen on EDT
		if (!SwingUtilities.isEventDispatchThread()) {
			SwingUtilities.invokeLater(this::refresh);
			return;
		}

		updateHeartbeatStatus();
		if (!clientLoggedIn) {
			renderLoggedOutState();
			return;
		}

		LocalDate today = LocalDate.now();

		// ---- Session view (unverified) ----
		if (selectedDays == 0) {
			long total = store.getSessionTotalXp();

			cardTitle.setText("Total XP (Session)");
			cardValue.setText(fmtUnverified(total));
			cardSub.setText(store.isSessionActive() ? "Live (unverified)" : "No active session");

			if (selectedSkill == null) {
				showTopSkillsSession();
			} else {
				showSingleSkillSession();
			}

			return;
		}

		// ---- Rolling window views (1/7/30) ----
		long totalXp = getTotalXpRolling(today, selectedDays);

		if (selectedSkill == null) {
			cardTitle.setText("Total XP (All skills)");
			cardValue.setText(fmt(totalXp));
			cardSub.setText("Last " + selectedDays + " day" + (selectedDays == 1 ? "" : "s"));

			showTopSkills(today);
		} else {
			long xpRange = store.getXpRollingDays(today, selectedSkill, selectedDays);
			double avgPerDay = (xpRange / (double) selectedDays);

			cardTitle.setText(selectedSkill.getName() + " XP");
			cardValue.setText(fmt(xpRange));
			cardSub.setText("Avg/day (" + selectedDays + "d): " + fmt(Math.round(avgPerDay)));

			showSingleSkill(today);
		}
	}

	// ============================================================
	// UI builders
	// ============================================================

	private JPanel buildHeader() {
		header.setForeground(Color.WHITE);
		header.setFont(header.getFont().deriveFont(Font.BOLD, 14f));

		subtitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);

		p.add(header);
		p.add(Box.createVerticalStrut(2));
		p.add(subtitle);

		return p;
	}

	private JPanel buildTimeframeRow() {
		JPanel timeframe = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		timeframe.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel lbl = new JLabel("Range:");
		lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		ButtonGroup rangeGroup = new ButtonGroup();
		rangeGroup.add(btnSession);
		rangeGroup.add(btn1d);
		rangeGroup.add(btn7d);
		rangeGroup.add(btn30d);

		// default
		btn7d.setSelected(true);

		// listeners
		btnSession.addActionListener(e -> {
			selectedDays = 0;
			refresh();
		});
		btn1d.addActionListener(e -> {
			selectedDays = 1;
			refresh();
		});
		btn7d.addActionListener(e -> {
			selectedDays = 7;
			refresh();
		});
		btn30d.addActionListener(e -> {
			selectedDays = 30;
			refresh();
		});

		timeframe.add(lbl);
		timeframe.add(btnSession);
		timeframe.add(btn1d);
		timeframe.add(btn7d);
		timeframe.add(btn30d);

		return timeframe;
	}

	private JPanel buildSummaryCard() {
		JPanel card = new JPanel();
		card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
		card.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		card.setBorder(cardBorder());

		cardTitle.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		cardValue.setForeground(Color.WHITE);
		cardValue.setFont(cardValue.getFont().deriveFont(Font.BOLD, 20f));

		cardSub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		card.add(cardTitle);
		card.add(Box.createVerticalStrut(4));
		card.add(cardValue);
		card.add(Box.createVerticalStrut(2));
		card.add(cardSub);

		return card;
	}

	private JComponent buildExportButton() {
		JButton export = new JButton("Copy Last Session JSON");
		export.addActionListener(e -> exportLastSessionHandler.run());
		return export;
	}

	private JPanel buildHeartbeatStatusBlock() {
		JPanel hb = new JPanel();
		hb.setLayout(new BoxLayout(hb, BoxLayout.Y_AXIS));
		hb.setBackground(ColorScheme.DARK_GRAY_COLOR);

		hbLine1.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hbLine2.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hbLine3.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hbLine4.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		hb.add(hbLine1);
		hb.add(Box.createVerticalStrut(2));
		hb.add(hbLine2);
		hb.add(Box.createVerticalStrut(2));
		hb.add(hbLine3);
		hb.add(Box.createVerticalStrut(2));
		hb.add(hbLine4);

		return hb;
	}

	private JPanel buildDetailBlock() {
		JPanel detail = new JPanel();
		detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
		detail.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		detail.setBorder(cardBorder());

		detailTitle.setForeground(Color.WHITE);
		detailTitle.setFont(detailTitle.getFont().deriveFont(Font.BOLD, 13f));

		detail.add(detailTitle);
		detail.add(Box.createVerticalStrut(6));

		// CardLayout switches between TOP and SINGLE view
		detailBody.setLayout(new CardLayout());
		detailBody.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		// Top skills list panel
		topListPanel.setLayout(new BoxLayout(topListPanel, BoxLayout.Y_AXIS));
		topListPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		for (int i = 0; i < topSkillLines.length; i++) {
			topSkillLines[i] = new JLabel(" ");
			topSkillLines[i].setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			topListPanel.add(topSkillLines[i]);

			if (i < topSkillLines.length - 1) {
				topListPanel.add(Box.createVerticalStrut(2));
			}
		}

		// Single skill detail panel
		singleSkillPanel.setLayout(new BoxLayout(singleSkillPanel, BoxLayout.Y_AXIS));
		singleSkillPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		detailLine1.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		detailLine2.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		detailLine3.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

		singleSkillPanel.add(detailLine1);
		singleSkillPanel.add(Box.createVerticalStrut(2));
		singleSkillPanel.add(detailLine2);
		singleSkillPanel.add(Box.createVerticalStrut(2));
		singleSkillPanel.add(detailLine3);

		detailBody.add(topListPanel, "TOP");
		detailBody.add(singleSkillPanel, "SINGLE");

		detail.add(detailBody);

		return detail;
	}

	private CompoundBorder cardBorder() {
		return new CompoundBorder(
				new LineBorder(ColorScheme.BORDER_COLOR),
				new EmptyBorder(8, 8, 8, 8));
	}

	// ============================================================
	// Data rendering helpers (rolling windows)
	// ============================================================

	private void showTopSkills(LocalDate today) {
		detailTitle.setText("Top skills (last " + selectedDays + "d)");
		showTopCard();

		List<SkillTotal> totals = new ArrayList<>();
		for (Skill s : Skill.values()) {
			if (s.getName().equalsIgnoreCase("Overall")) {
				continue;
			}

			long xp = store.getXpRollingDays(today, s, selectedDays);
			if (xp > 0) {
				totals.add(new SkillTotal(s, xp));
			}
		}

		totals.sort(Comparator.comparingLong((SkillTotal st) -> st.xp).reversed());

		for (int i = 0; i < topSkillLines.length; i++) {
			if (i < totals.size()) {
				SkillTotal st = totals.get(i);
				topSkillLines[i].setText((i + 1) + ". " + st.skill.getName() + "  +" + fmt(st.xp));
			} else {
				topSkillLines[i].setText(" ");
			}
		}
	}

	private void showSingleSkill(LocalDate today) {
		detailTitle.setText(selectedSkill.getName() + " breakdown");
		showSingleCard();

		long xp1 = store.getXpRollingDays(today, selectedSkill, 1);
		long xp7 = store.getXpRollingDays(today, selectedSkill, 7);
		long xp30 = store.getXpRollingDays(today, selectedSkill, 30);

		detailLine1.setText("Today: +" + fmt(xp1));
		detailLine2.setText("7d: +" + fmt(xp7));
		detailLine3.setText("30d: +" + fmt(xp30));
	}

	private long getTotalXpRolling(LocalDate today, int days) {
		long sum = 0;
		for (Skill s : Skill.values()) {
			if (s.getName().equalsIgnoreCase("Overall")) {
				continue;
			}
			sum += store.getXpRollingDays(today, s, days);
		}
		return sum;
	}

	// ============================================================
	// Data rendering helpers (session view)
	// ============================================================

	private void showTopSkillsSession() {
		detailTitle.setText("Top skills (session)");
		showTopCard();

		List<SkillTotal> totals = new ArrayList<>();
		for (Skill s : Skill.values()) {
			if (s.getName().equalsIgnoreCase("Overall")) {
				continue;
			}

			long xp = store.getSessionXp(s);
			if (xp > 0) {
				totals.add(new SkillTotal(s, xp));
			}
		}

		totals.sort(Comparator.comparingLong((SkillTotal st) -> st.xp).reversed());

		for (int i = 0; i < topSkillLines.length; i++) {
			if (i < totals.size()) {
				SkillTotal st = totals.get(i);
				topSkillLines[i].setText("<html>" + (i + 1) + ". " + st.skill.getName()
						+ "  <i>*+" + fmt(st.xp) + "</i></html>");
			} else {
				topSkillLines[i].setText(" ");
			}
		}
	}

	private void showSingleSkillSession() {
		detailTitle.setText(selectedSkill.getName() + " (session)");
		showSingleCard();

		long sxp = store.getSessionXp(selectedSkill);

		// Entire label must be HTML if we want italics + asterisk
		detailLine1.setText("<html>Session: <i>*+" + fmt(sxp) + "</i></html>");
		detailLine2.setText(" ");
		detailLine3.setText(" ");
	}

	// ============================================================
	// Detail panel switching
	// ============================================================

	private void showTopCard() {
		CardLayout cl = (CardLayout) detailBody.getLayout();
		cl.show(detailBody, "TOP");
	}

	private void showSingleCard() {
		CardLayout cl = (CardLayout) detailBody.getLayout();
		cl.show(detailBody, "SINGLE");
	}

	// ============================================================
	// Skill grid
	// ============================================================

	private JPanel buildSkillGrid() {
		JPanel grid = new JPanel(new GridLayout(0, 6, 4, 4));
		grid.setBorder(new EmptyBorder(6, 6, 6, 6));
		grid.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		ButtonGroup group = new ButtonGroup();

		// ALL button
		JToggleButton all = makeChip("ALL");
		all.setSelected(true);
		all.addActionListener(e -> {
			selectedSkill = null;
			refresh();
		});
		group.add(all);
		grid.add(all);

		// Skill buttons
		for (Skill s : Skill.values()) {
			if (s.getName().equalsIgnoreCase("Overall")) {
				continue;
			}

			JToggleButton b = makeSkillButton(s);
			group.add(b);
			grid.add(b);
		}

		return grid;
	}

	private JToggleButton makeSkillButton(Skill skill) {
		ImageIcon icon = new ImageIcon(skillIconManager.getSkillImage(skill, true));
		JToggleButton b = new JToggleButton(icon);

		b.setToolTipText(skill.getName());
		b.setFocusPainted(false);
		b.setOpaque(true);
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setBorder(new LineBorder(ColorScheme.BORDER_COLOR));

		b.addChangeListener(e -> b.setBorder(new LineBorder(
				b.isSelected() ? ColorScheme.BRAND_ORANGE : ColorScheme.BORDER_COLOR,
				1)));

		b.addActionListener(e -> {
			selectedSkill = skill;
			refresh();
		});

		return b;
	}

	private static JToggleButton makeChip(String text) {
		JToggleButton b = new JToggleButton(text);

		b.setFocusPainted(false);
		b.setOpaque(true);
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(Color.WHITE);
		b.setBorder(new LineBorder(ColorScheme.BORDER_COLOR));

		b.addChangeListener(e -> b.setBorder(new LineBorder(
				b.isSelected() ? ColorScheme.BRAND_ORANGE : ColorScheme.BORDER_COLOR,
				1)));

		return b;
	}

	// ============================================================
	// Heartbeat status rendering
	// ============================================================

	private void updateHeartbeatStatus() {
		if (heartbeatStatusSupplier == null) {
			clientLoggedIn = false;
			hbLine1.setText("Uploads: Off");
			hbLine2.setText("Pending: -");
			hbLine3.setText("Last: -");
			hbLine4.setText("");
			return;
		}

		HeartbeatStatus s = heartbeatStatusSupplier.get();
		if (s == null) {
			clientLoggedIn = false;
			return;
		}
		clientLoggedIn = s.loggedIn;

		String uploadsLabel = s.uploadsEnabled ? "On" : "Off";
		if (s.readinessHint != null && !s.readinessHint.isBlank()) {
			uploadsLabel += " (" + s.readinessHint + ")";
		}
		hbLine1.setText("Uploads: " + uploadsLabel);

		hbLine2.setText("Pending: " + (s.hasPending ? "Yes" : "No"));

		if (s.lastSuccessMillis > 0) {
			hbLine3.setText("Last success: " + ago(s.lastSuccessMillis));
		} else if (s.lastAttemptMillis > 0) {
			hbLine3.setText("Last attempt: " + ago(s.lastAttemptMillis));
		} else {
			hbLine3.setText("Last: Never");
		}

		if (s.lastError != null && !s.lastError.isBlank()) {
			hbLine4.setText("Error: " + s.lastError);
		} else {
			hbLine4.setText("");
		}
	}

	private void renderLoggedOutState() {
		cardTitle.setText(selectedSkill == null ? "Total XP" : selectedSkill.getName() + " XP");
		cardValue.setText("---");
		cardSub.setText("Log in to view data");

		if (selectedSkill == null) {
			detailTitle.setText("Top skills");
			showTopCard();
			topSkillLines[0].setText("Log in to view tracked totals");
			for (int i = 1; i < topSkillLines.length; i++) {
				topSkillLines[i].setText(" ");
			}
			return;
		}

		detailTitle.setText(selectedSkill.getName() + " breakdown");
		showSingleCard();
		detailLine1.setText("Log in to view tracked totals");
		detailLine2.setText(" ");
		detailLine3.setText(" ");
	}

	private static String ago(long millis) {
		long sec = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
		if (sec < 60)
			return sec + "s ago";

		long min = sec / 60;
		if (min < 60)
			return min + "m ago";

		long hr = min / 60;
		return hr + "h ago";
	}

	// ============================================================
	// Formatting helpers / DTOs
	// ============================================================

	private static String fmt(long n) {
		return NumberFormat.getInstance(Locale.US).format(n);
	}

	private static String fmtUnverified(long n) {
		return "<html><i>*" + fmt(n) + "</i></html>";
	}

	private static final class SkillTotal {
		private final Skill skill;
		private final long xp;

		private SkillTotal(Skill skill, long xp) {
			this.skill = skill;
			this.xp = xp;
		}
	}

	public static final class HeartbeatStatus {
		public boolean uploadsEnabled;
		public boolean loggedIn;
		public boolean sessionActive;
		public boolean hasPending;
		public long lastAttemptMillis;
		public long lastSuccessMillis;
		public String lastError;
		public String readinessHint;
	}
}
