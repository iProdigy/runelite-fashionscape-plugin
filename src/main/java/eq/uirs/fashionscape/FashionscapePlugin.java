package eq.uirs.fashionscape;

import com.google.inject.Provides;
import eq.uirs.fashionscape.data.ItemInteractions;
import eq.uirs.fashionscape.panel.FashionscapePanel;
import eq.uirs.fashionscape.swap.SwapManager;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.swing.SwingUtilities;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.PlayerChanged;
import net.runelite.api.events.UsernameChanged;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.menus.MenuManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.http.api.item.ItemStats;

@PluginDescriptor(
	name = "Fashionscape",
	description = "Previews combinations of equipment by changing the player's local appearance"
)
@Slf4j
public class FashionscapePlugin extends Plugin
{
	public static final File OUTFITS_DIR = new File(RuneLite.RUNELITE_DIR, "outfits");
	public static final Pattern PROFILE_PATTERN = Pattern.compile("^(\\w+):(-?\\d+).*");

	private static final String CONFIG_GROUP = "fashionscape";
	private static final String COPY_PLAYER = "Copy-outfit";
	private static final Set<Integer> ITEM_ID_DUPES = new HashSet<>();

	// combined set of all items to skip when searching (bad items, dupes, non-standard if applicable)
	public static Set<Integer> getItemIdsToExclude(FashionscapeConfig config)
	{
		Set<Integer> result = ITEM_ID_DUPES;
		result.addAll(ItemInteractions.BAD_ITEM_IDS);
		if (config.excludeNonStandardItems())
		{
			result.addAll(ItemInteractions.NON_STANDARD_ITEMS);
		}
		return result;
	}

	@Value
	private static class ItemIcon
	{
		int modelId;
		short[] colorsToReplace;
		short[] texturesToReplace;
	}

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ItemManager itemManager;

	@Inject
	private SwapManager swapManager;

	@Inject
	private FashionscapeConfig config;

	@Inject
	private Provider<MenuManager> menuManager;

	private FashionscapePanel panel;
	private NavigationButton navButton;

	@Provides
	FashionscapeConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FashionscapeConfig.class);
	}

	@Override
	protected void startUp()
	{
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panelicon.png");
		panel = injector.getInstance(FashionscapePanel.class);
		navButton = NavigationButton.builder()
			.tooltip("Fashionscape")
			.icon(icon)
			.panel(panel)
			.priority(8)
			.build();
		clientToolbar.addNavigation(navButton);
		refreshMenuEntries();
		clientThread.invokeLater(() -> {
			populateDupes();
			swapManager.startUp();
		});
	}

	@Override
	protected void shutDown()
	{
		menuManager.get().removePlayerMenuItem(COPY_PLAYER);
		clientThread.invokeLater(() -> swapManager.shutDown());
		clientToolbar.removeNavigation(navButton);
		ITEM_ID_DUPES.clear();
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged event)
	{
		Player player = event.getPlayer();
		if (player != null && player == client.getLocalPlayer())
		{
			swapManager.onPlayerChanged();
			if (panel != null)
			{
				panel.onPlayerChanged(player);
			}
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.EQUIPMENT.getId())
		{
			swapManager.onEquipmentChanged();
		}
	}

	@Subscribe
	public void onUsernameChanged(UsernameChanged event)
	{
		swapManager.clearRealIds();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(CONFIG_GROUP))
		{
			if (event.getKey().equals(FashionscapeConfig.KEY_EXCLUDE_NON_STANDARD))
			{
				// reload displayed results
				clientThread.invokeLater(() -> {
					populateDupes();
					panel.reloadResults();
				});
			}
			else if (event.getKey().equals(FashionscapeConfig.KEY_IMPORT_MENU_ENTRY))
			{
				refreshMenuEntries();
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			populateDupes();
		}
		if (panel != null)
		{
			panel.onGameStateChanged(event);
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		if (event.getMenuAction() == MenuAction.RUNELITE_PLAYER && event.getMenuOption().equals(COPY_PLAYER))
		{
			SwingUtilities.invokeLater(() ->
			{
				if (!navButton.isSelected())
				{
					Runnable onSelect = navButton.getOnSelect();
					if (onSelect != null)
					{
						onSelect.run();
					}
				}
			});
			Player p = client.getCachedPlayers()[event.getId()];
			if (p == null)
			{
				return;
			}
			swapManager.copyOutfit(p.getPlayerComposition());
		}
	}

	private void populateDupes()
	{
		ITEM_ID_DUPES.clear();
		Set<Integer> ids = new HashSet<>();
		Set<ItemIcon> itemIcons = new HashSet<>();
		Set<Integer> skips = FashionscapePlugin.getItemIdsToExclude(config);
		for (int i = 0; i < client.getItemCount(); i++)
		{
			int canonical = itemManager.canonicalize(i);
			if (skips.contains(canonical))
			{
				continue;
			}
			ItemComposition itemComposition = itemManager.getItemComposition(canonical);
			ItemStats itemStats = itemManager.getItemStats(canonical, false);
			if (!ids.contains(itemComposition.getId()) && itemStats != null && itemStats.isEquipable())
			{
				// Check if the results already contain the same item image
				ItemIcon itemIcon = new ItemIcon(itemComposition.getInventoryModel(),
					itemComposition.getColorToReplaceWith(), itemComposition.getTextureToReplaceWith());
				if (itemIcons.contains(itemIcon))
				{
					ITEM_ID_DUPES.add(canonical);
					continue;
				}
				itemIcons.add(itemIcon);
				ids.add(itemComposition.getId());
			}
		}
	}

	private void refreshMenuEntries()
	{
		if (config.copyMenuEntry())
		{
			menuManager.get().addPlayerMenuItem(COPY_PLAYER);
		}
		else
		{
			menuManager.get().removePlayerMenuItem(COPY_PLAYER);
		}
	}

}
