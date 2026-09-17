package com.grant9008.personalspace;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.LinkBrowser;

/**
 * The Personal Space sidebar.
 *
 * <p>Top to bottom: title with the on/off switch, a "Crowd" card with the everyday settings, a folded-away Troubleshooting section (status, test mode, live checks and a
 * copyable report), and links to support the developer or report a problem.
 *
 * <p>Swing thread only. The plugin pushes a fresh {@link Snapshot} a couple of times a second via
 * {@link #update}, and calls {@link #refreshControls} whenever the config changes (including from
 * RuneLite's own settings screen). Controls write straight to the config, so both stay in sync.
 */
final class PersonalSpacePanel extends PluginPanel
{
	static final String SUPPORT_URL = "https://github.com/grant9008/personal-space#support-the-developer";
	static final String ISSUES_URL = "https://github.com/grant9008/personal-space/issues";

	private static final int TEXT_WIDTH_PX = 150;
	private static final Color SELECTED_TEXT = new Color(30, 30, 30);
	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;

	private final ConfigManager configManager;
	private final PersonalSpaceConfig config;

	private final ToggleSwitch activeSwitch = new ToggleSwitch();
	private final JLabel statusDot = new JLabel();
	private final JLabel statusTitle = new JLabel();
	/** One line under the title saying what the plugin is doing right now, e.g. "Spreading 7 players on 3 tiles". */
	private final JLabel liveLine = new JLabel();
	/** A second line saying what shape you're standing in, e.g. "You're in a row along the counter or wall". */
	private final JLabel shapeLine = new JLabel();
	private final JLabel liveDot = new JLabel();
	private final JLabel statusDetail = new JLabel();

	private final JSlider perTileSlider = new JSlider(PersonalSpaceConfig.MIN_STACK, PersonalSpaceConfig.MAX_STACK, PersonalSpaceConfig.DEFAULT_STACK);
	private final JLabel perTileValue = new JLabel();
	private final PillGroup<Integer> spacingPills = new PillGroup<>(
		new Integer[]{PersonalSpaceConfig.SPACING_CLOSE, PersonalSpaceConfig.SPACING_NORMAL, PersonalSpaceConfig.SPACING_WIDE},
		new String[]{"Close", "Normal", "Wide"});
	private final JSlider spacingSlider = new JSlider(PersonalSpaceConfig.MIN_SPACING, PersonalSpaceConfig.MAX_SPACING, PersonalSpaceConfig.SPACING_WIDE);
	private final JLabel spacingValue = new JLabel();
	private final PillGroup<PersonalSpaceConfig.Arrangement> arrangementPills = new PillGroup<>(
		PersonalSpaceConfig.Arrangement.values(), labels(PersonalSpaceConfig.Arrangement.values()));
	private final PillGroup<PersonalSpaceConfig.Pose> posePills = new PillGroup<>(
		PersonalSpaceConfig.Pose.values(), labels(PersonalSpaceConfig.Pose.values()));

	private final ToggleSwitch includeMeSwitch = new ToggleSwitch();
	private final ToggleSwitch smallGroupsSwitch = new ToggleSwitch();
	private final ToggleSwitch combatSwitch = new ToggleSwitch();

	private final JPanel troubleshootingBody = new JPanel(new GridBagLayout());
	private final JLabel troubleshootingHeader = new JLabel("Troubleshooting");
	private final ToggleSwitch testModeSwitch = new ToggleSwitch();
	private final JSlider testOffsetSlider = new JSlider(PersonalSpaceConfig.MIN_TEST_OFFSET, PersonalSpaceConfig.MAX_TEST_OFFSET, 32);
	private final JLabel testOffsetValue = new JLabel();
	private final JPanel checksPanel = new JPanel(new GridBagLayout());
	private final List<JLabel[]> checkRows = new ArrayList<>();
	private final JButton copyButton = new JButton("Copy report");
	private final Timer copyReset = new Timer(2500, e -> copyButton.setText("Copy report"));

	private Snapshot last = new Snapshot();

	PersonalSpacePanel(ConfigManager configManager, PersonalSpaceConfig config)
	{
		// No automatic scroll wrapper: the settings scroll on their own, and the links stay pinned
		// to the bottom of the sidebar out of the way.
		super(false);
		this.configManager = configManager;
		this.config = config;

		setLayout(new BorderLayout());
		setBorder(new EmptyBorder(0, 0, 0, 0));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel content = new JPanel(new GridBagLayout());
		content.setBackground(ColorScheme.DARK_GRAY_COLOR);
		content.setBorder(new EmptyBorder(10, 10, 10, 10));
		GridBagConstraints c = column();
		content.add(buildHeader(), c);

		c.gridy++;
		content.add(buildCrowdCard(), c);

		c.gridy++;
		content.add(buildTroubleshooting(), c);

		// Keep the content at the top of the scroll area.
		JPanel top = new JPanel(new BorderLayout());
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.add(content, BorderLayout.NORTH);
		JScrollPane scroll = new JScrollPane(top);
		scroll.setBorder(null);
		scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		scroll.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(scroll, BorderLayout.CENTER);
		add(buildFooter(), BorderLayout.SOUTH);

		wireControls();
		refreshControls();
		update(last);
	}

	// ---- called by the plugin ----------------------------------------------------------

	/** Swing thread. Show a fresh snapshot. Only the Troubleshooting section shows it, and only while open. */
	void update(Snapshot s)
	{
		last = s;
		StatusSummary.Headline h = StatusSummary.headline(s);
		liveDot.setIcon(new Dot(colorFor(h.level), 7));
		liveLine.setText(h.title);
		shapeLine.setText(s.yourShape == null ? "" : wrap(s.yourShape));
		shapeLine.setVisible(s.yourShape != null);
		if (!troubleshootingBody.isVisible())
		{
			return;
		}

		statusDot.setIcon(new Dot(colorFor(h.level), 10));
		statusTitle.setText(wrap(h.title));
		statusDetail.setText(wrap(h.detail));

		List<StatusSummary.Check> checks = StatusSummary.checks(s);
		if (checks.size() != checkRows.size())
		{
			rebuildCheckRows(checks.size());
		}
		for (int i = 0; i < checks.size(); i++)
		{
			StatusSummary.Check check = checks.get(i);
			JLabel[] row = checkRows.get(i);
			row[0].setIcon(new Dot(colorFor(check.level), 7));
			row[0].setText(check.label);
			row[1].setText(check.value);
		}
	}

	/** Swing thread. Set every control from the current config. Controls only write on a user action. */
	void refreshControls()
	{
		activeSwitch.setOn(config.active());
		setSliderQuietly(perTileSlider, clamp(config.maxStack(), PersonalSpaceConfig.MIN_STACK, PersonalSpaceConfig.MAX_STACK));
		perTileValue.setText(perTileSlider.getValue() + " players");
		setSliderQuietly(spacingSlider, clamp(config.spacing(), PersonalSpaceConfig.MIN_SPACING, PersonalSpaceConfig.MAX_SPACING));
		showSpacing(spacingSlider.getValue());
		arrangementPills.select(config.arrangement());
		posePills.select(config.pose());
		includeMeSwitch.setOn(config.includeLocalPlayer());
		smallGroupsSwitch.setOn(config.smallGroupsClose());
		combatSwitch.setOn(config.pauseInCombat());
		testModeSwitch.setOn(config.mode() == PersonalSpaceConfig.Mode.TEST_SHIFT_ME);
		setSliderQuietly(testOffsetSlider, clamp(config.testOffset(), PersonalSpaceConfig.MIN_TEST_OFFSET, PersonalSpaceConfig.MAX_TEST_OFFSET));
		testOffsetValue.setText(testOffsetSlider.getValue() + " units");
		setEverydayEnabled(config.active());
	}

	/** Package-private for the screenshot harness. */
	void setTroubleshootingOpen(boolean open)
	{
		troubleshootingBody.setVisible(open);
		troubleshootingHeader.setIcon(new Arrow(open));
		if (open)
		{
			update(last);
		}
		revalidate();
		repaint();
	}

	// ---- building ----------------------------------------------------------------------

	private JPanel buildHeader()
	{
		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setOpaque(false);
		p.setBorder(new EmptyBorder(0, 0, 10, 0));

		JLabel title = new JLabel("Personal Space");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		try
		{
			BufferedImage icon = ImageUtil.loadImageResource(PersonalSpacePlugin.class, "panel_icon.png");
			title.setIcon(new ImageIcon(icon));
			title.setIconTextGap(7);
		}
		catch (RuntimeException e)
		{
			// no icon, no problem
		}
		p.add(title, BorderLayout.WEST);
		activeSwitch.setToolTipText("Turn spreading out crowds on or off");
		p.add(activeSwitch, BorderLayout.EAST);

		JPanel live = new JPanel(new BorderLayout(5, 0));
		live.setOpaque(false);
		live.setBorder(new EmptyBorder(6, 1, 0, 0));
		liveDot.setIcon(new Dot(ColorScheme.MEDIUM_GRAY_COLOR, 7));
		liveDot.setBorder(new EmptyBorder(1, 0, 0, 0));
		live.add(liveDot, BorderLayout.WEST);
		liveLine.setFont(FontManager.getRunescapeSmallFont());
		liveLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		liveLine.setToolTipText("What Personal Space is doing right now. Open Troubleshooting for details.");
		live.add(liveLine, BorderLayout.CENTER);

		shapeLine.setFont(FontManager.getRunescapeSmallFont());
		shapeLine.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		shapeLine.setBorder(new EmptyBorder(3, 13, 0, 0));
		shapeLine.setToolTipText("The shape your own tile is using. Change it with Arrangement, or with the Spacing slider and Auto-space.");
		shapeLine.setVisible(false);

		JPanel lines = new JPanel(new BorderLayout());
		lines.setOpaque(false);
		lines.add(live, BorderLayout.NORTH);
		lines.add(shapeLine, BorderLayout.SOUTH);
		p.add(lines, BorderLayout.SOUTH);
		return p;
	}

	private JPanel buildStatus()
	{
		JPanel card = new JPanel(new BorderLayout(8, 0));
		card.setBackground(CARD);
		card.setBorder(new EmptyBorder(8, 8, 8, 8));

		statusDot.setVerticalAlignment(SwingConstants.TOP);
		statusDot.setBorder(new EmptyBorder(3, 0, 0, 0));
		card.add(statusDot, BorderLayout.WEST);

		JPanel text = new JPanel(new BorderLayout(0, 3));
		text.setOpaque(false);
		statusTitle.setFont(FontManager.getRunescapeBoldFont());
		statusTitle.setForeground(Color.WHITE);
		statusDetail.setFont(FontManager.getRunescapeSmallFont());
		statusDetail.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.add(statusTitle, BorderLayout.NORTH);
		text.add(statusDetail, BorderLayout.CENTER);
		card.add(text, BorderLayout.CENTER);
		return card;
	}

	private JPanel buildCrowdCard()
	{
		JPanel card = card("Crowd");
		GridBagConstraints c = cardConstraints();

		card.add(labelWithValue("Players per tile", perTileValue), c);
		c.gridy++;
		perTileSlider.setToolTipText("How many players on one tile get their own spot. 5 is the sweet spot; up to 10 for drop-party chaos.");
		card.add(slider(perTileSlider), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 4, 0);
		card.add(labelWithValue("Spacing", spacingValue), c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		spacingPills.setToolTipText("Quick picks. Fine-tune with the slider below.");
		card.add(spacingPills, c);
		c.gridy++;
		spacingSlider.setToolTipText("How far apart players are drawn. Changes show up live. At bank counters, walls and fires, Auto-space keeps people close: turn it off to use this slider everywhere.");
		card.add(slider(spacingSlider), c);

		c.gridy++;
		c.insets = new Insets(6, 0, 4, 0);
		card.add(fieldLabel("Arrangement"), c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		arrangementPills.setToolTipText("How everyone on a tile is drawn up. Smart: along whatever they're facing - a counter, a wall, an anvil, a fire - and a ring out in the open, where there's nothing to line up along. Circle: always a ring. Line: side by side anywhere. Arc: a curve, like the crowd round an anvil.");
		card.add(arrangementPills, c);

		c.gridy++;
		c.insets = new Insets(6, 0, 4, 0);
		card.add(fieldLabel("Pose for 2 or 3 players"), c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		posePills.setToolTipText("Natural: the way they really face. Angled: turned halfway towards each other, like a photo. Facing: towards each other. Players at an anvil, booth or fire keep facing it.");
		card.add(posePills, c);

		c.gridy++;
		c.insets = new Insets(10, 0, 0, 0);
		card.add(switchRow("Auto-space", smallGroupsSwitch,
			"On: the plugin picks sensible distances whatever the Spacing slider says, so groups of 2 or 3 stay close together and people at a bank counter, a wall or a fire stand shoulder to shoulder. Turn off to unlock the slider: it then sets exactly how far apart everyone stands, anywhere, handy for photos."), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 0, 0);
		card.add(switchRow("Move my character too", includeMeSwitch,
			"Off: you stay where you are and others step around you. On: you take a spot too, at the front, so what you're doing looks right."), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 0, 0);
		card.add(switchRow("Pause while I'm fighting", combatSwitch,
			"On: everyone is shown where they really stand while you fight, and for a few seconds after. Keep this on for raids and group bosses, where standing on the same tile matters. Turn it off to keep seeing the crowd during ordinary fights like training or slayer."), c);

		c.gridy++;
		c.insets = new Insets(10, 0, 0, 0);
		JLabel note = new JLabel(wrap("Visual only. Players are drawn shifted so you can see them, but you click them where they really stand."));
		note.setFont(FontManager.getRunescapeSmallFont());
		note.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		card.add(note, c);
		return wrapCard(card);
	}

	private JPanel buildTroubleshooting()
	{
		JPanel p = new JPanel(new GridBagLayout());
		p.setOpaque(false);
		GridBagConstraints c = column();

		troubleshootingHeader.setFont(FontManager.getRunescapeSmallFont());
		troubleshootingHeader.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		troubleshootingHeader.setIcon(new Arrow(false));
		troubleshootingHeader.setIconTextGap(6);
		troubleshootingHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		troubleshootingHeader.setBorder(new EmptyBorder(4, 0, 4, 0));
		troubleshootingHeader.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				setTroubleshootingOpen(!troubleshootingBody.isVisible());
			}
		});
		p.add(troubleshootingHeader, c);

		troubleshootingBody.setOpaque(false);
		GridBagConstraints b = column();
		b.insets = new Insets(4, 0, 6, 0);
		troubleshootingBody.add(buildStatus(), b);

		b.gridy++;
		b.insets = new Insets(4, 0, 0, 0);
		troubleshootingBody.add(switchRow("Test: shift only me", testModeSwitch,
			"Ignores everyone else and draws your own character a little to the east, to check the effect works."), b);

		b.gridy++;
		troubleshootingBody.add(labelWithValue("Test distance", testOffsetValue), b);
		b.gridy++;
		b.insets = new Insets(0, 0, 0, 0);
		testOffsetSlider.setToolTipText("How far test mode shifts your character. 128 units is one tile.");
		troubleshootingBody.add(slider(testOffsetSlider), b);

		b.gridy++;
		b.insets = new Insets(8, 0, 0, 0);
		checksPanel.setBackground(CARD);
		checksPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
		troubleshootingBody.add(checksPanel, b);

		b.gridy++;
		copyButton.setFocusable(false);
		copyButton.setToolTipText("Copies everything above as text, to paste into a bug report.");
		copyButton.addActionListener(e -> copyReport());
		troubleshootingBody.add(copyButton, b);

		c.gridy++;
		p.add(troubleshootingBody, c);
		troubleshootingBody.setVisible(false);
		return p;
	}

	private JPanel buildFooter()
	{
		JPanel p = new JPanel(new GridBagLayout());
		p.setBackground(ColorScheme.DARK_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, ColorScheme.DARKER_GRAY_COLOR),
			new EmptyBorder(6, 10, 8, 10)));
		GridBagConstraints c = column();

		JLabel support = new JLabel("Support the developer", new HeartIcon(ColorScheme.BRAND_ORANGE), SwingConstants.CENTER);
		support.setIconTextGap(6);
		support.setToolTipText("Personal Space is free. If you enjoy it, you can chip in here.");
		link(support, ColorScheme.LIGHT_GRAY_COLOR, () -> LinkBrowser.browse(SUPPORT_URL));
		p.add(support, c);

		c.gridy++;
		c.insets = new Insets(3, 0, 0, 0);
		JPanel small = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
		small.setOpaque(false);
		JLabel report = new JLabel("Report a problem");
		link(report, ColorScheme.MEDIUM_GRAY_COLOR, () -> LinkBrowser.browse(ISSUES_URL));
		small.add(report);
		JLabel version = new JLabel("v" + PersonalSpacePlugin.VERSION);
		version.setFont(FontManager.getRunescapeSmallFont());
		version.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		small.add(version);
		p.add(small, c);
		return p;
	}

	private void rebuildCheckRows(int count)
	{
		checksPanel.removeAll();
		checkRows.clear();
		GridBagConstraints c = new GridBagConstraints();
		c.gridy = 0;
		c.insets = new Insets(1, 0, 1, 0);
		for (int i = 0; i < count; i++)
		{
			JLabel label = new JLabel();
			label.setFont(FontManager.getRunescapeSmallFont());
			label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			label.setIconTextGap(6);
			JLabel value = new JLabel();
			value.setFont(FontManager.getRunescapeSmallFont());
			value.setForeground(Color.WHITE);
			value.setHorizontalAlignment(SwingConstants.RIGHT);

			c.gridx = 0;
			c.weightx = 1;
			c.anchor = GridBagConstraints.WEST;
			c.fill = GridBagConstraints.HORIZONTAL;
			checksPanel.add(label, c);
			c.gridx = 1;
			c.weightx = 0;
			c.anchor = GridBagConstraints.EAST;
			c.fill = GridBagConstraints.NONE;
			checksPanel.add(value, c);
			c.gridy++;
			checkRows.add(new JLabel[]{label, value});
		}
		checksPanel.revalidate();
		checksPanel.repaint();
	}

	private void wireControls()
	{
		activeSwitch.onToggle(on ->
		{
			write(PersonalSpaceConfig.KEY_ACTIVE, on);
			setEverydayEnabled(on);
		});
		perTileSlider.addChangeListener(e ->
		{
			perTileValue.setText(perTileSlider.getValue() + " players");
			if (perTileSlider.getValue() != config.maxStack())
			{
				write(PersonalSpaceConfig.KEY_MAX_STACK, perTileSlider.getValue());
			}
		});
		spacingPills.onSelect(v ->
		{
			spacingSlider.setValue(v);
			write(PersonalSpaceConfig.KEY_SPACING, v);
		});
		spacingSlider.addChangeListener(e ->
		{
			int v = spacingSlider.getValue();
			showSpacing(v);
			// Written while dragging too, so players move live as the slider moves.
			if (v != config.spacing())
			{
				write(PersonalSpaceConfig.KEY_SPACING, v);
			}
		});
		arrangementPills.onSelect(v -> write(PersonalSpaceConfig.KEY_ARRANGEMENT, v));
		posePills.onSelect(v -> write(PersonalSpaceConfig.KEY_POSE, v));
		includeMeSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_INCLUDE_LOCAL, on));
		smallGroupsSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_SMALL_GROUPS_CLOSE, on));
		combatSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_PAUSE_IN_COMBAT, on));
		testModeSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_MODE,
			on ? PersonalSpaceConfig.Mode.TEST_SHIFT_ME : PersonalSpaceConfig.Mode.SPREAD));
		testOffsetSlider.addChangeListener(e ->
		{
			testOffsetValue.setText(testOffsetSlider.getValue() + " units");
			if (!testOffsetSlider.getValueIsAdjusting() && testOffsetSlider.getValue() != config.testOffset())
			{
				write(PersonalSpaceConfig.KEY_TEST_OFFSET, testOffsetSlider.getValue());
			}
		});
	}

	private <T> void write(String key, T value)
	{
		if (configManager != null)
		{
			configManager.setConfiguration(PersonalSpaceConfig.GROUP, key, value);
		}
	}

	/** Dim the everyday settings while the whole effect is switched off. */
	private void setEverydayEnabled(boolean enabled)
	{
		perTileSlider.setEnabled(enabled);
		spacingPills.setEnabled(enabled);
		spacingSlider.setEnabled(enabled);
		arrangementPills.setEnabled(enabled);
		posePills.setEnabled(enabled);
		includeMeSwitch.setEnabled(enabled);
		smallGroupsSwitch.setEnabled(enabled);
		combatSwitch.setEnabled(enabled);
	}

	/** Show the spacing as a share of a tile, and light up the matching quick pick if there is one. */
	private void showSpacing(int units)
	{
		spacingValue.setText(Math.round(units * 100f / 128) + "% of a tile");
		spacingPills.selectOrNone(units);
	}

	private void copyReport()
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(StatusSummary.report(last)), null);
			copyButton.setText("Copied!");
		}
		catch (IllegalStateException e)
		{
			copyButton.setText("Clipboard busy, try again");
		}
		copyReset.setRepeats(false);
		copyReset.restart();
	}

	// ---- small helpers -----------------------------------------------------------------

	private static GridBagConstraints column()
	{
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = 0;
		c.gridy = 0;
		c.weightx = 1;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.anchor = GridBagConstraints.NORTH;
		return c;
	}

	private static GridBagConstraints cardConstraints()
	{
		GridBagConstraints c = column();
		c.gridy = 1; // row 0 is the card title
		c.insets = new Insets(0, 0, 4, 0);
		return c;
	}

	/** A card with a title row; content goes from grid row 1. */
	private static JPanel card(String title)
	{
		JPanel card = new JPanel(new GridBagLayout());
		card.setBackground(CARD);
		card.setBorder(new EmptyBorder(8, 9, 9, 9));
		JLabel heading = new JLabel(title.toUpperCase());
		heading.setFont(FontManager.getRunescapeSmallFont());
		heading.setForeground(ColorScheme.BRAND_ORANGE);
		GridBagConstraints c = column();
		c.insets = new Insets(0, 0, 6, 0);
		card.add(heading, c);
		return card;
	}

	/** Space below each card. */
	private static JPanel wrapCard(JPanel card)
	{
		JPanel outer = new JPanel(new BorderLayout());
		outer.setOpaque(false);
		outer.setBorder(new EmptyBorder(0, 0, 8, 0));
		outer.add(card, BorderLayout.CENTER);
		return outer;
	}

	private static JLabel fieldLabel(String text)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return l;
	}

	private static JPanel labelWithValue(String text, JLabel value)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.add(fieldLabel(text), BorderLayout.WEST);
		value.setFont(FontManager.getRunescapeSmallFont());
		value.setForeground(Color.WHITE);
		row.add(value, BorderLayout.EAST);
		return row;
	}

	/** Move a slider to a value without fighting the user mid-drag. */
	private static void setSliderQuietly(JSlider slider, int value)
	{
		if (!slider.getValueIsAdjusting() && slider.getValue() != value)
		{
			slider.setValue(value);
		}
	}

	private static JSlider slider(JSlider s)
	{
		s.setOpaque(false);
		s.setFocusable(false);
		s.setPreferredSize(new Dimension(0, s.getPreferredSize().height));
		return s;
	}

	private static JPanel switchRow(String text, ToggleSwitch toggle, String tooltip)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeFont());
		label.setForeground(Color.WHITE);
		label.setToolTipText(tooltip);
		toggle.setToolTipText(tooltip);
		row.add(label, BorderLayout.WEST);
		row.add(toggle, BorderLayout.EAST);
		// Clicking the words flips the switch too.
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				toggle.click();
			}
		});
		return row;
	}

	private static void link(JLabel label, Color color, Runnable action)
	{
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		label.setHorizontalAlignment(SwingConstants.CENTER);
		label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				action.run();
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				label.setForeground(Color.WHITE);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				label.setForeground(color);
			}
		});
	}

	private static String[] labels(Object[] values)
	{
		String[] out = new String[values.length];
		for (int i = 0; i < values.length; i++)
		{
			out[i] = values[i].toString();
		}
		return out;
	}

	private static Color colorFor(StatusSummary.Level level)
	{
		switch (level)
		{
			case OK:
				return ColorScheme.PROGRESS_COMPLETE_COLOR;
			case PAUSED:
				return ColorScheme.PROGRESS_INPROGRESS_COLOR;
			case PROBLEM:
				return ColorScheme.PROGRESS_ERROR_COLOR;
			case WAITING:
			default:
				return ColorScheme.MEDIUM_GRAY_COLOR;
		}
	}

	private static int clamp(int v, int min, int max)
	{
		return Math.max(min, Math.min(max, v));
	}

	/** HTML so long text wraps inside the narrow sidebar. */
	private static String wrap(String text)
	{
		String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><div style='width:" + TEXT_WIDTH_PX + "px'>" + escaped + "</div></html>";
	}

	// ---- controls ----------------------------------------------------------------------

	/** An on/off switch drawn in RuneLite's colours. Fires only on a user click. */
	private static final class ToggleSwitch extends JComponent
	{
		private static final int W = 32;
		private static final int H = 18;
		private boolean on;
		private Consumer<Boolean> listener = v ->
		{
		};

		ToggleSwitch()
		{
			setPreferredSize(new Dimension(W, H));
			setMinimumSize(new Dimension(W, H));
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					click();
				}
			});
		}

		void onToggle(Consumer<Boolean> listener)
		{
			this.listener = listener;
		}

		void setOn(boolean on)
		{
			if (this.on != on)
			{
				this.on = on;
				repaint();
			}
		}

		void click()
		{
			if (!isEnabled())
			{
				return;
			}
			on = !on;
			repaint();
			listener.accept(on);
		}

		@Override
		public void setEnabled(boolean enabled)
		{
			super.setEnabled(enabled);
			repaint();
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			int y = (getHeight() - H) / 2;
			int x = getWidth() - W;
			Color track = on ? ColorScheme.BRAND_ORANGE : ColorScheme.MEDIUM_GRAY_COLOR;
			if (!isEnabled())
			{
				track = track.darker().darker();
			}
			g2.setColor(track);
			g2.fillRoundRect(x, y, W, H, H, H);
			int knob = H - 4;
			int knobX = on ? x + W - knob - 2 : x + 2;
			g2.setColor(isEnabled() ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
			g2.fillOval(knobX, y + 2, knob, knob);
			g2.dispose();
		}
	}

	/** A row of buttons where exactly one is picked. Fires only on a user click. */
	private static final class PillGroup<T> extends JPanel
	{
		private final T[] values;
		private final JLabel[] pills;
		private int selected = -1;
		private int hovered = -1;
		private Consumer<T> listener = v ->
		{
		};

		PillGroup(T[] values, String[] labels)
		{
			super(new GridLayout(1, values.length, 4, 0));
			setOpaque(false);
			this.values = values;
			this.pills = new JLabel[values.length];
			for (int i = 0; i < values.length; i++)
			{
				final int index = i;
				JLabel pill = new JLabel(labels[i], SwingConstants.CENTER);
				pill.setOpaque(true);
				pill.setFont(FontManager.getRunescapeSmallFont());
				pill.setBorder(new EmptyBorder(6, 2, 6, 2));
				pill.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				pill.addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseClicked(MouseEvent e)
					{
						if (!PillGroup.this.isEnabled())
						{
							return;
						}
						select(PillGroup.this.values[index]);
						listener.accept(PillGroup.this.values[index]);
					}

					@Override
					public void mouseEntered(MouseEvent e)
					{
						hovered = index;
						restyle();
					}

					@Override
					public void mouseExited(MouseEvent e)
					{
						hovered = -1;
						restyle();
					}
				});
				pills[i] = pill;
				add(pill);
			}
			restyle();
		}

		void onSelect(Consumer<T> listener)
		{
			this.listener = listener;
		}

		void select(T value)
		{
			for (int i = 0; i < values.length; i++)
			{
				if (values[i].equals(value))
				{
					selected = i;
				}
			}
			restyle();
		}

		/** Like {@link #select}, but highlights nothing if the value isn't one of the buttons. */
		void selectOrNone(T value)
		{
			selected = -1;
			select(value);
		}

		@Override
		public void setToolTipText(String text)
		{
			super.setToolTipText(text);
			for (JLabel pill : pills)
			{
				pill.setToolTipText(text);
			}
		}

		@Override
		public void setEnabled(boolean enabled)
		{
			super.setEnabled(enabled);
			restyle();
		}

		private void restyle()
		{
			for (int i = 0; i < pills.length; i++)
			{
				Color bg;
				Color fg;
				if (i == selected)
				{
					bg = ColorScheme.BRAND_ORANGE;
					fg = SELECTED_TEXT;
				}
				else if (i == hovered && isEnabled())
				{
					bg = ColorScheme.MEDIUM_GRAY_COLOR;
					fg = Color.WHITE;
				}
				else
				{
					bg = ColorScheme.DARK_GRAY_COLOR;
					fg = ColorScheme.LIGHT_GRAY_COLOR;
				}
				if (!isEnabled())
				{
					bg = bg.darker();
					fg = fg.darker();
				}
				pills[i].setBackground(bg);
				pills[i].setForeground(fg);
			}
		}
	}

	/** A small filled circle, used as a status light. */
	private static final class Dot implements Icon
	{
		private final Color color;
		private final int size;

		Dot(Color color, int size)
		{
			this.color = color;
			this.size = size;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			g2.fillOval(x, y, size, size);
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return size;
		}

		@Override
		public int getIconHeight()
		{
			return size;
		}
	}

	/** A small chevron: pointing right when folded, down when open. */
	private static final class Arrow implements Icon
	{
		private final boolean open;

		Arrow(boolean open)
		{
			this.open = open;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(ColorScheme.LIGHT_GRAY_COLOR);
			g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			if (open)
			{
				g2.drawPolyline(new int[]{x + 1, x + 4, x + 7}, new int[]{y + 3, y + 6, y + 3}, 3);
			}
			else
			{
				g2.drawPolyline(new int[]{x + 3, x + 6, x + 3}, new int[]{y + 1, y + 4, y + 7}, 3);
			}
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return 8;
		}

		@Override
		public int getIconHeight()
		{
			return 8;
		}
	}

	/** A small heart. */
	private static final class HeartIcon implements Icon
	{
		private final Color color;

		HeartIcon(Color color)
		{
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			Path2D heart = new Path2D.Double();
			heart.moveTo(x + 7, y + 12);
			heart.curveTo(x - 2, y + 6, x + 1, y - 1, x + 7, y + 3);
			heart.curveTo(x + 13, y - 1, x + 16, y + 6, x + 7, y + 12);
			heart.closePath();
			g2.fill(heart);
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return 14;
		}

		@Override
		public int getIconHeight()
		{
			return 13;
		}
	}
}
