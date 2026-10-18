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
import javax.swing.JOptionPane;
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
	/** A new issue on the same page, its title started so ideas stand apart from problems. */
	static final String IDEA_URL = "https://github.com/grant9008/personal-space/issues/new?title=Idea%3A%20";

	private static final int TEXT_WIDTH_PX = 150;
	/** Tooltips wrap at this width, rather than running in one line across the whole screen. */
	private static final int TIP_WIDTH_PX = 220;
	private static final Color SELECTED_TEXT = new Color(30, 30, 30);
	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;

	private final ConfigManager configManager;
	private final PersonalSpaceConfig config;

	private final ToggleSwitch activeSwitch = new ToggleSwitch();
	private final JLabel statusDot = new JLabel();
	private final JLabel statusTitle = new JLabel();
	/** One line under the title saying what the plugin is doing right now, e.g. "Spreading 7 players on 3 tiles". */
	private final JLabel liveLine = new JLabel();
	private final JLabel liveDot = new JLabel();
	private final JLabel statusDetail = new JLabel();

	private final JSlider perTileSlider = new JSlider(PersonalSpaceConfig.MIN_STACK, PersonalSpaceConfig.MAX_STACK, PersonalSpaceConfig.DEFAULT_STACK);
	private final JLabel perTileValue = new JLabel();
	private final JSlider hideBelowSlider = new JSlider(PersonalSpaceConfig.MIN_HIDE_BELOW, PersonalSpaceConfig.MAX_HIDE_BELOW, PersonalSpaceConfig.MIN_HIDE_BELOW);
	private final JLabel hideBelowValue = new JLabel();
	private final PillGroup<Integer> spacingPills = new PillGroup<>(
		new Integer[]{PersonalSpaceConfig.SPACING_CLOSE, PersonalSpaceConfig.SPACING_NORMAL, PersonalSpaceConfig.SPACING_WIDE},
		new String[]{"Close", "Normal", "Wide"});
	private final JSlider spacingSlider = new JSlider(PersonalSpaceConfig.MIN_SPACING, PersonalSpaceConfig.MAX_SPACING, PersonalSpaceConfig.SPACING_WIDE);
	private final JLabel spacingValue = new JLabel();
	private final PillGroup<PersonalSpaceConfig.Arrangement> arrangementPills = new PillGroup<>(
		PersonalSpaceConfig.Arrangement.values(), labels(PersonalSpaceConfig.Arrangement.values()));
	private final PillGroup<PersonalSpaceConfig.Pose> posePills = new PillGroup<>(
		PersonalSpaceConfig.Pose.values(), labels(PersonalSpaceConfig.Pose.values()));
	private final PillGroup<PersonalSpaceConfig.Alchers> alcherPills = new PillGroup<>(
		PersonalSpaceConfig.Alchers.values(), labels(PersonalSpaceConfig.Alchers.values()));

	private final ToggleSwitch includeMeSwitch = new ToggleSwitch();
	private final ToggleSwitch inFrontSwitch = new ToggleSwitch();
	private final ToggleSwitch namesSwitch = new ToggleSwitch();
	private final ToggleSwitch hoverSwitch = new ToggleSwitch();
	private final ToggleSwitch hideChatSwitch = new ToggleSwitch();
	private final JSlider chatRangeSlider = new JSlider(0, PersonalSpaceConfig.MAX_CHAT_RANGE, 0);
	private final JLabel chatRangeValue = new JLabel();
	private final ToggleSwitch arrowSwitch = new ToggleSwitch();
	private final ToggleSwitch smallGroupsSwitch = new ToggleSwitch();
	private final ToggleSwitch combatSwitch = new ToggleSwitch();

	private final JPanel troubleshootingBody = new JPanel(new GridBagLayout());
	private final JLabel troubleshootingHeader = new JLabel("Troubleshooting");
	private final ToggleSwitch depthTestSwitch = new ToggleSwitch();
	private final ToggleSwitch walkBusySwitch = new ToggleSwitch();
	private final ToggleSwitch sitSwitch = new ToggleSwitch();
	private final ToggleSwitch clickPastSwitch = new ToggleSwitch();
	private final JPanel checksPanel = new JPanel(new GridBagLayout());
	private final List<JLabel[]> checkRows = new ArrayList<>();
	private final JButton copyButton = new JButton("Copy report");
	private final JButton gpuButton = new JButton("Turn on GPU");
	private Runnable turnOnGpu = () -> { };
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
		content.add(buildExtrasCard(), c);

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

	/** What the Turn on GPU button does. */
	void onTurnOnGpu(Runnable action)
	{
		turnOnGpu = action;
	}

	/** Swing thread. Show a fresh snapshot. Only the Troubleshooting section shows it, and only while open. */
	void update(Snapshot s)
	{
		last = s;
		StatusSummary.Headline h = StatusSummary.headline(s);
		liveDot.setIcon(new Dot(colorFor(h.level), 7));
		liveLine.setText(h.title);
		gpuButton.setVisible(s.renderer == null && !s.hdEnabled && s.gate != Snapshot.Gate.NOT_LOGGED_IN);
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
		setSliderQuietly(hideBelowSlider, clamp(config.hideBelowLevel(), PersonalSpaceConfig.MIN_HIDE_BELOW, PersonalSpaceConfig.MAX_HIDE_BELOW));
		hideBelowValue.setText(hideBelowText(hideBelowSlider.getValue()));
		setSliderQuietly(spacingSlider, clamp(config.spacing(), PersonalSpaceConfig.MIN_SPACING, PersonalSpaceConfig.MAX_SPACING));
		showSpacing(spacingSlider.getValue());
		arrangementPills.select(config.arrangement());
		posePills.select(config.pose());
		alcherPills.select(config.otherAlchers());
		namesSwitch.setOn(config.namesFollowPlayers());
		hoverSwitch.setOn(config.hoverShowsWho());
		hideChatSwitch.setOn(config.hideTheirChat());
		setSliderQuietly(chatRangeSlider, clamp(config.hideChatFurtherThan(), 0, PersonalSpaceConfig.MAX_CHAT_RANGE));
		chatRangeValue.setText(chatRangeText(chatRangeSlider.getValue()));
		arrowSwitch.setOn(config.hoverArrow());
		includeMeSwitch.setOn(config.includeLocalPlayer());
		inFrontSwitch.setOn(config.drawMeInFront());
		smallGroupsSwitch.setOn(config.smallGroupsClose());
		combatSwitch.setOn(config.pauseInCombat());
		depthTestSwitch.setOn(config.depthBetweenPlayers());
		walkBusySwitch.setOn(config.walkWhileBusy());
		sitSwitch.setOn(config.idlePlayersSit());
		clickPastSwitch.setOn(config.clickPastMoved());
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
		activeSwitch.setToolTipText(tip("Turns Personal Space on or off. Off shows the game exactly as normal."));
		p.add(activeSwitch, BorderLayout.EAST);

		JPanel live = new JPanel(new BorderLayout(5, 0));
		live.setOpaque(false);
		live.setBorder(new EmptyBorder(6, 1, 0, 0));
		liveDot.setIcon(new Dot(ColorScheme.MEDIUM_GRAY_COLOR, 7));
		liveDot.setBorder(new EmptyBorder(1, 0, 0, 0));
		live.add(liveDot, BorderLayout.WEST);
		liveLine.setFont(FontManager.getRunescapeSmallFont());
		liveLine.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		liveLine.setToolTipText(tip("What Personal Space is doing right now."));
		live.add(liveLine, BorderLayout.CENTER);

		// Offered only while no GPU renderer is running and 117 HD isn't switched on: Personal Space
		// can't do anything without one, and finding the GPU plugin in RuneLite's list is a hunt.
		// Bright, as it's the one thing to do while it shows: nothing is spread out until it's pressed.
		gpuButton.setFocusPainted(false);
		gpuButton.setFont(FontManager.getRunescapeBoldFont());
		gpuButton.setForeground(Color.BLACK);
		gpuButton.setBackground(ColorScheme.BRAND_ORANGE);
		gpuButton.setOpaque(true);
		gpuButton.setContentAreaFilled(true);
		gpuButton.setBorderPainted(false);
		gpuButton.setBorder(new EmptyBorder(7, 10, 7, 10));
		gpuButton.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
		gpuButton.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mouseEntered(java.awt.event.MouseEvent e)
			{
				gpuButton.setBackground(ColorScheme.BRAND_ORANGE.brighter());
			}

			@Override
			public void mouseExited(java.awt.event.MouseEvent e)
			{
				gpuButton.setBackground(ColorScheme.BRAND_ORANGE);
			}
		});
		gpuButton.setToolTipText(tip("Personal Space needs RuneLite's GPU plugin, or 117 HD, to draw crowds. This switches the GPU plugin on for you."));
		gpuButton.addActionListener(e -> turnOnGpu.run());
		gpuButton.setVisible(false);
		JPanel gpuRow = new JPanel(new BorderLayout());
		gpuRow.setOpaque(false);
		gpuRow.setBorder(new EmptyBorder(6, 0, 0, 0));
		gpuRow.add(gpuButton, BorderLayout.CENTER);

		JPanel under = new JPanel(new BorderLayout());
		under.setOpaque(false);
		under.add(gpuRow, BorderLayout.SOUTH);

		JPanel lines = new JPanel(new BorderLayout());
		lines.setOpaque(false);
		lines.add(live, BorderLayout.NORTH);
		lines.add(under, BorderLayout.SOUTH);
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

	/**
	 * The crowd card: how crowds are spread, and the switches that are on by default. Everything off
	 * by default is in {@link #buildExtrasCard}, so the everyday settings aren't lost among extras.
	 */
	private JPanel buildCrowdCard()
	{
		JLabel recommended = new JLabel("Use recommended");
		recommended.setFont(FontManager.getRunescapeSmallFont());
		recommended.setToolTipText(tip("Puts the crowd settings back to what looks best in most places: 5 per tile, Wide, Smart shape, Auto spacing on, Natural pose. Your other switches aren't touched."));
		link(recommended, ColorScheme.MEDIUM_GRAY_COLOR, this::useRecommended);
		JPanel card = card("Crowd", recommended);
		GridBagConstraints c = cardConstraints();

		card.add(labelWithValue("Players per tile", perTileValue), c);
		c.gridy++;
		perTileSlider.setToolTipText(tip("How many people on one tile get spread out. 5 suits most places. Raise it if a busy bank still leaves a pile in the middle."));
		card.add(slider(perTileSlider), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 4, 0);
		card.add(labelWithValue("Spacing", spacingValue), c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		spacingPills.setToolTipText(tip("How far apart spread-out people stand. Pick one, or drag the slider for anything in between."));
		card.add(spacingPills, c);
		c.gridy++;
		spacingSlider.setToolTipText(tip("How far apart spread-out people stand. With Auto spacing on, banks, counters and fires stay close anyway."));
		card.add(slider(spacingSlider), c);

		c.gridy++;
		c.insets = new Insets(6, 0, 4, 0);
		card.add(fieldLabel("Shape"), c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		arrangementPills.setToolTipText(tip("The shape people spread into. Smart: picks one for the place (rows at banks and counters, one crowd round an anvil or fire, a ring in the open). Circle, Line and Arc: that shape everywhere."));
		card.add(arrangementPills, c);

		c.gridy++;
		c.insets = new Insets(6, 0, 4, 0);
		card.add(fieldLabel("Pose for 2 or 3 players"), c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		posePills.setToolTipText(tip("How 2 or 3 people standing together are turned. Natural: the way they really face. Angled: half turned to each other. Facing: face to face."));
		card.add(posePills, c);

		c.gridy++;
		c.insets = new Insets(10, 0, 0, 0);
		card.add(switchRow("Auto spacing", smallGroupsSwitch,
			"On (recommended): the gaps suit the place, staying close at banks, counters, walls and fires and for small groups. Off: the Spacing slider sets every gap, everywhere, which is handy for photos."), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 0, 0);
		card.add(switchRow("Give me a spot", includeMeSwitch,
			"On (recommended): you're spread out too, taking a spot in the crowd like everyone else (the front one, at a bank counter). Off: you always stay exactly where you really stand, and the people on your tile spread out around you."), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 0, 0);
		card.add(switchRow("Depth between players", depthTestSwitch,
			"On (recommended): in a crowd, shields, capes and hats stay properly in front of or behind the people next to them as you turn the camera. Off: everyone is drawn whole, one over another, so gear pops between people. Only worth turning off if something looks wrong. GPU plugin only."), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 0, 0);
		card.add(switchRow("Walk while busy", walkBusySwitch,
			"On (recommended): people moved while they're doing something (alching, cooking) are shown walking to their spot. Off: they glide there in the pose of what they're doing."), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 0, 0);
		card.add(switchRow("Hover shows who", hoverSwitch,
			"On: hover someone who's been moved to see their name and level. You still click people where they really stand."), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 0, 0);
		card.add(switchRow("Use items through players", clickPastSwitch,
			"On (recommended): when you use an item or spell on something (a fire, a booth), moved or hidden people don't take the left-click. Right-click still lists them."), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 0, 0);
		card.add(switchRow("Pause while I'm fighting", combatSwitch,
			"On (recommended): while you're fighting, everyone goes back to where they really stand, so you can see who's where. Keep it on for raids and bosses; turn it off for training spots like rock crabs."), c);

		return wrapCard(card);
	}

	/** The extras card: everything off by default (hiding players and chat, alchers, sitting, and so on). */
	private JPanel buildExtrasCard()
	{
		JLabel offNote = new JLabel("Off by default");
		offNote.setFont(FontManager.getRunescapeSmallFont());
		offNote.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		JPanel card = card("Extras", offNote);
		GridBagConstraints c = cardConstraints();

		c.insets = new Insets(0, 0, 4, 0);
		JPanel hideBelowLabel = labelWithValue("Hide players below", hideBelowValue);
		card.add(hideBelowLabel, c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		String hideBelowTip = tip("Hide anyone under this combat level, bodies and overhead chat, like Entity Hider. Set 4 to hide the level-3 bots at the Grand Exchange. Friends, friends chat and clan are never hidden. Off in PvP areas and while you fight.");
		hideBelowLabel.setToolTipText(hideBelowTip);
		hideBelowSlider.setToolTipText(hideBelowTip);
		card.add(slider(hideBelowSlider), c);
		c.gridy++;
		c.insets = new Insets(4, 0, 0, 0);
		JPanel hideChat = switchRow("Hide their chat too", hideChatSwitch,
			"On: the players hidden by 'Hide players below' are also taken out of your chat box. Does nothing while that's Off.");
		// Small, like the other labels in this card: it belongs to the slider above it.
		for (java.awt.Component part : hideChat.getComponents())
		{
			if (part instanceof JLabel)
			{
				part.setFont(FontManager.getRunescapeSmallFont());
				part.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			}
		}
		card.add(hideChat, c);

		c.gridy++;
		c.insets = new Insets(8, 0, 4, 0);
		JPanel chatRangeLabel = labelWithValue("Hide chat further than", chatRangeValue);
		card.add(chatRangeLabel, c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		String chatRangeTip = tip("Hide public chat from anyone further away than this many tiles, over their heads and in the chat box. Handy at an anvil next to a noisy bank. Friends, friends chat and clan always show. 0 is off.");
		chatRangeLabel.setToolTipText(chatRangeTip);
		chatRangeSlider.setToolTipText(chatRangeTip);
		card.add(slider(chatRangeSlider), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 4, 0);
		card.add(fieldLabel("Alchers behavior"), c);
		c.gridy++;
		c.insets = new Insets(0, 0, 0, 0);
		alcherPills.setToolTipText(tip("How other players' skilling spells look to you: alching, superheat, charge orb and enchanting. Normal: as the game shows them. Still: no spell animation or glow, so a crowd of alchers stops flashing. Party: each cast becomes a random dance. Your own spells never change."));
		card.add(alcherPills, c);

		c.gridy++;
		c.insets = new Insets(10, 0, 0, 0);
		card.add(switchRow("Idle players sit", sitSwitch, "Off by default. On: other players who've done nothing for 30 seconds (AFK) sit down. They stand up as soon as they move, talk or do something. Only on your screen."), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 0, 0);
		card.add(switchRow("Draw me in front", inFrontSwitch,
			"On: you're always drawn on top of your crowd, so you never lose sight of yourself. Off: people between you and the camera cover you, as they really would."), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 0, 0);
		card.add(switchRow("Names over players", namesSwitch,
			"On: names float over where people are drawn, not over where they stand. Uses Player Indicators' settings (who, colours, icons). Turn off Player Indicators' own names so you don't see two."), c);

		c.gridy++;
		c.insets = new Insets(8, 0, 0, 0);
		card.add(switchRow("Arrow to their tile", arrowSwitch,
			"On: hover someone who's been moved and an arrow points to where they really stand."), c);

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
		b.insets = new Insets(8, 0, 0, 0);
		checksPanel.setBackground(CARD);
		checksPanel.setBorder(new EmptyBorder(6, 8, 6, 8));
		troubleshootingBody.add(checksPanel, b);

		b.gridy++;
		copyButton.setFocusable(false);
		copyButton.setToolTipText(tip("Copies a full report, ready to paste into a bug report."));
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

		// Quiet links, then the version: nothing here needs to shout. Feedback on one line, support
		// and the version on the next, as three links don't fit side by side in the sidebar. (On
		// three lines it took a lot of the sidebar for very little.)
		JPanel links = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
		links.setOpaque(false);
		JLabel report = new JLabel("Report a problem");
		report.setToolTipText(tip("Something wrong? Click Copy report under Troubleshooting, then paste it into a new issue with a screenshot."));
		link(report, ColorScheme.MEDIUM_GRAY_COLOR, () -> LinkBrowser.browse(ISSUES_URL));
		links.add(report);
		JLabel idea = new JLabel("Suggest an idea");
		idea.setToolTipText(tip("Got an idea for Personal Space? Share it in a new issue."));
		link(idea, ColorScheme.MEDIUM_GRAY_COLOR, () -> LinkBrowser.browse(IDEA_URL));
		links.add(idea);
		p.add(links, c);

		c.gridy++;
		c.insets = new Insets(3, 0, 0, 0);
		JPanel more = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
		more.setOpaque(false);
		JLabel support = new JLabel("Support the dev", new HeartIcon(ColorScheme.MEDIUM_GRAY_COLOR), SwingConstants.CENTER);
		support.setIconTextGap(4);
		support.setToolTipText(tip("Personal Space is free. If you enjoy it, you can chip in here."));
		link(support, ColorScheme.MEDIUM_GRAY_COLOR, () -> LinkBrowser.browse(SUPPORT_URL));
		more.add(support);
		JLabel version = new JLabel("v" + PersonalSpacePlugin.VERSION, SwingConstants.CENTER);
		version.setFont(FontManager.getRunescapeSmallFont());
		version.setForeground(ColorScheme.MEDIUM_GRAY_COLOR);
		more.add(version);
		p.add(more, c);
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
		hideBelowSlider.addChangeListener(e ->
		{
			hideBelowValue.setText(hideBelowText(hideBelowSlider.getValue()));
			if (hideBelowSlider.getValue() != config.hideBelowLevel())
			{
				write(PersonalSpaceConfig.KEY_HIDE_BELOW, hideBelowSlider.getValue());
			}
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
		alcherPills.onSelect(v -> write(PersonalSpaceConfig.KEY_OTHER_ALCHERS, v));
		namesSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_NAMES, on));
		hoverSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_HOVER, on));
		hideChatSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_HIDE_CHAT, on));
		chatRangeSlider.addChangeListener(e ->
		{
			chatRangeValue.setText(chatRangeText(chatRangeSlider.getValue()));
			if (chatRangeSlider.getValue() != config.hideChatFurtherThan())
			{
				write(PersonalSpaceConfig.KEY_CHAT_RANGE, chatRangeSlider.getValue());
			}
		});
		arrowSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_HOVER_ARROW, on));
		includeMeSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_INCLUDE_LOCAL, on));
		inFrontSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_DRAW_ME_IN_FRONT, on));
		smallGroupsSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_SMALL_GROUPS_CLOSE, on));
		combatSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_PAUSE_IN_COMBAT, on));
		depthTestSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_DEPTH, on));
		walkBusySwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_WALK_WHILE_BUSY, on));
		sitSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_IDLE_SIT, on));
		clickPastSwitch.onToggle(on -> write(PersonalSpaceConfig.KEY_CLICK_PAST, on));
	}

	/**
	 * The Use recommended link: after asking, the crowd settings go back to their defaults. Packed
	 * tighter than that, people's gear overlaps and swaps in front and behind as the camera turns.
	 */
	private void useRecommended()
	{
		int answer = JOptionPane.showConfirmDialog(this,
			"Set the crowd settings back to the recommended ones?\n\n"
				+ "5 players per tile, Wide spacing, Smart shape, Auto spacing on, Natural pose.\n"
				+ "Your other switches stay as they are.",
			"Personal Space", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (answer != JOptionPane.YES_OPTION)
		{
			return;
		}
		write(PersonalSpaceConfig.KEY_MAX_STACK, PersonalSpaceConfig.DEFAULT_STACK);
		write(PersonalSpaceConfig.KEY_SPACING, PersonalSpaceConfig.SPACING_WIDE);
		write(PersonalSpaceConfig.KEY_ARRANGEMENT, PersonalSpaceConfig.Arrangement.AUTO);
		write(PersonalSpaceConfig.KEY_SMALL_GROUPS_CLOSE, true);
		write(PersonalSpaceConfig.KEY_POSE, PersonalSpaceConfig.Pose.NATURAL);
		refreshControls();
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
		hideBelowSlider.setEnabled(enabled);
		hideChatSwitch.setEnabled(enabled);
		chatRangeSlider.setEnabled(enabled);
		spacingPills.setEnabled(enabled);
		spacingSlider.setEnabled(enabled);
		arrangementPills.setEnabled(enabled);
		posePills.setEnabled(enabled);
		alcherPills.setEnabled(enabled);
		includeMeSwitch.setEnabled(enabled);
		inFrontSwitch.setEnabled(enabled);
		depthTestSwitch.setEnabled(enabled);
		walkBusySwitch.setEnabled(enabled);
		sitSwitch.setEnabled(enabled);
		clickPastSwitch.setEnabled(enabled);
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
		return card(title, null);
	}

	/** A card with a heading, and something small on the right of it, if given. */
	private static JPanel card(String title, JComponent right)
	{
		JPanel card = new JPanel(new GridBagLayout());
		card.setBackground(CARD);
		card.setBorder(new EmptyBorder(8, 9, 9, 9));
		JLabel heading = new JLabel(title.toUpperCase());
		heading.setFont(FontManager.getRunescapeSmallFont());
		heading.setForeground(ColorScheme.BRAND_ORANGE);
		GridBagConstraints c = column();
		c.insets = new Insets(0, 0, 6, 0);
		if (right == null)
		{
			card.add(heading, c);
			return card;
		}
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.add(heading, BorderLayout.WEST);
		row.add(right, BorderLayout.EAST);
		card.add(row, c);
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

	private static String chatRangeText(int tiles)
	{
		return tiles <= 0 ? "Off" : tiles == 1 ? "1 tile" : tiles + " tiles";
	}

	private static String hideBelowText(int level)
	{
		// The far left is Off, and the next step already hides the level-3s: nobody is below 3.
		return level <= PersonalSpaceConfig.MIN_HIDE_BELOW ? "Off" : "level " + level;
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
		label.setToolTipText(tip(tooltip));
		toggle.setToolTipText(tip(tooltip));
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
	/** A tooltip that wraps at a readable width: Swing draws a plain one as a single line. */
	private static String tip(String text)
	{
		String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
		return "<html><div style='width:" + TIP_WIDTH_PX + "px'>" + escaped + "</div></html>";
	}

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
