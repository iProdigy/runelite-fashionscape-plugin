package eq.uirs.fashionscape.swap;

import eq.uirs.fashionscape.FashionscapeConfig;
import eq.uirs.fashionscape.FashionscapePlugin;
import eq.uirs.fashionscape.colors.ColorScorer;
import eq.uirs.fashionscape.data.BootsColor;
import eq.uirs.fashionscape.data.ClothingColor;
import eq.uirs.fashionscape.data.ColorType;
import eq.uirs.fashionscape.data.Colorable;
import eq.uirs.fashionscape.data.HairColor;
import eq.uirs.fashionscape.data.IdleAnimationID;
import eq.uirs.fashionscape.data.ItemInteractions;
import eq.uirs.fashionscape.data.Kit;
import eq.uirs.fashionscape.data.SkinColor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.PlayerComposition;
import net.runelite.api.kit.KitType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.game.ItemManager;
import net.runelite.http.api.item.ItemEquipmentStats;
import net.runelite.http.api.item.ItemStats;

/**
 * Singleton class that maintains the memory and logic of swapping items through the plugin
 */
@Singleton
@Slf4j
public class SwapManager
{
	public static final Map<Integer, Kit> KIT_MAP = new HashMap<>();
	// when player's kit info is not known, fall back to showing some default values
	private static final Map<KitType, Integer> FALLBACK_MALE_KITS = new HashMap<>();
	private static final Map<KitType, Integer> FALLBACK_FEMALE_KITS = new HashMap<>();
	private static final Map<KitType, List<Kit>> ALL_KITS_MAP;
	private static final String KIT_SUFFIX = "_KIT";
	private static final String COLOR_SUFFIX = "_COLOR";

	static
	{
		FALLBACK_MALE_KITS.put(KitType.HAIR, Kit.BALD.getKitId());
		FALLBACK_MALE_KITS.put(KitType.JAW, Kit.GOATEE.getKitId());
		FALLBACK_MALE_KITS.put(KitType.TORSO, Kit.PLAIN.getKitId());
		FALLBACK_MALE_KITS.put(KitType.ARMS, Kit.REGULAR.getKitId());
		FALLBACK_MALE_KITS.put(KitType.LEGS, Kit.PLAIN_L.getKitId());
		FALLBACK_MALE_KITS.put(KitType.HANDS, Kit.PLAIN_H.getKitId());
		FALLBACK_MALE_KITS.put(KitType.BOOTS, Kit.SMALL.getKitId());

		FALLBACK_FEMALE_KITS.put(KitType.HAIR, Kit.PIGTAILS.getKitId());
		FALLBACK_FEMALE_KITS.put(KitType.JAW, -256);
		FALLBACK_FEMALE_KITS.put(KitType.TORSO, Kit.SIMPLE.getKitId());
		FALLBACK_FEMALE_KITS.put(KitType.ARMS, Kit.SHORT_SLEEVES.getKitId());
		FALLBACK_FEMALE_KITS.put(KitType.LEGS, Kit.PLAIN_LF.getKitId());
		FALLBACK_FEMALE_KITS.put(KitType.HANDS, Kit.PLAIN_HF.getKitId());
		FALLBACK_FEMALE_KITS.put(KitType.BOOTS, Kit.SMALL_F.getKitId());

		ALL_KITS_MAP = Arrays.stream(Kit.values())
			.collect(Collectors.groupingBy(Kit::getKitType));

		for (Kit value : Kit.values())
		{
			KIT_MAP.put(value.getKitId(), value);
		}
	}

	@Inject
	private FashionscapeConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ColorScorer colorScorer;

	@Inject
	private ChatMessageManager chatMessageManager;

	private final SavedSwaps savedSwaps = new SavedSwaps();
	private final SwapDiffHistory swapDiffHistory = new SwapDiffHistory(s -> this.restore(s, true));
	// player's real kit ids, e.g., hairstyles, base clothing
	private final Map<KitType, Integer> realKitIds = new HashMap<>();

	private Boolean isFemale;
	private SwapDiff hoverSwapDiff;

	@Value
	private static class Candidate
	{
		public int itemId;
		public KitType slot;
	}

	public void startUp()
	{
		checkForBaseIds();
		refreshAllSwaps();
	}

	public void shutDown()
	{
		savedSwaps.removeListeners();
		swapDiffHistory.removeListeners();
		clear();
	}

	public void clear()
	{
		hoverSwapDiff = null;
		savedSwaps.clear();
		realKitIds.clear();
		swapDiffHistory.clear();
	}

	public void addEventListener(SwapEventListener<? extends SwapEvent> listener)
	{
		savedSwaps.addEventListener(listener);
	}

	public void addUndoQueueChangeListener(Consumer<Integer> listener)
	{
		swapDiffHistory.addUndoQueueChangeListener(listener);
	}

	public void addRedoQueueChangeListener(Consumer<Integer> listener)
	{
		swapDiffHistory.addRedoQueueChangeListener(listener);
	}

	public boolean isSlotLocked(KitType slot)
	{
		return savedSwaps.isSlotLocked(slot);
	}

	public boolean isKitLocked(KitType slot)
	{
		return savedSwaps.isKitLocked(slot);
	}

	public boolean isItemLocked(KitType slot)
	{
		return savedSwaps.isItemLocked(slot);
	}

	public boolean isColorLocked(ColorType type)
	{
		return savedSwaps.isColorLocked(type);
	}

	public void toggleItemLocked(KitType slot)
	{
		savedSwaps.toggleItemLocked(slot);
	}

	public void toggleKitLocked(KitType slot)
	{
		savedSwaps.toggleKitLocked(slot);
	}

	public void toggleColorLocked(ColorType type)
	{
		savedSwaps.toggleColorLocked(type);
	}

	// this should only be called from the client thread
	public void refreshAllSwaps()
	{
		Map<KitType, Integer> savedItemEquipIds = savedSwaps.itemEntries().stream().collect(
			Collectors.toMap(Map.Entry::getKey, e -> e.getValue() + 512)
		);
		Map<KitType, Integer> savedKitEquipIds = savedSwaps.kitEntries().stream().collect(
			Collectors.toMap(Map.Entry::getKey, e -> e.getValue() + 256)
		);
		savedItemEquipIds.putAll(savedKitEquipIds);
		for (CompoundSwap c : CompoundSwap.fromMap(savedItemEquipIds))
		{
			this.swap(c, SwapMode.PREVIEW);
		}
	}

	/**
	 * Undoes the last action performed.
	 * Can only be called from the client thread.
	 */
	public void undoLastSwap()
	{
		if (client.getLocalPlayer() != null)
		{
			swapDiffHistory.undoLast();
		}
	}

	public boolean canUndo()
	{
		return swapDiffHistory.undoSize() > 0;
	}

	/**
	 * Redoes the last action that was undone.
	 * Can only be called from the client thread.
	 */
	public void redoLastSwap()
	{
		if (client.getLocalPlayer() != null)
		{
			swapDiffHistory.redoLast();
		}
	}

	public boolean canRedo()
	{
		return swapDiffHistory.redoSize() > 0;
	}

	/**
	 * Checks for the player's actual kits and colors so that they can be reverted
	 */
	public void checkForBaseIds()
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		PlayerComposition playerComposition = player.getPlayerComposition();
		if (playerComposition == null)
		{
			return;
		}
		isFemale = playerComposition.isFemale();
		for (KitType kitType : KitType.values())
		{
			Integer kitId = kitIdFor(kitType);
			if (kitId != null)
			{
				if (kitId >= 0 && !realKitIds.containsKey(kitType))
				{
					realKitIds.put(kitType, kitId);
				}
			}
		}
	}

	public void importSwaps(
		Map<KitType, Integer> newItems,
		Map<KitType, Integer> newKits)
	{
		clientThread.invokeLater(() -> {
			Map<KitType, Integer> itemSwaps = newItems.entrySet().stream()
				.filter(e -> !savedSwaps.isItemLocked(e.getKey()))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			Map<KitType, Integer> kitSwaps = newKits.entrySet().stream()
				.filter(e -> !savedSwaps.isKitLocked(e.getKey()))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			Map<KitType, Integer> itemEquipSwaps = itemSwaps.entrySet().stream().collect(
				Collectors.toMap(Map.Entry::getKey, e -> e.getValue() + 512)
			);
			Map<KitType, Integer> kitEquipSwaps = kitSwaps.entrySet().stream().collect(
				Collectors.toMap(Map.Entry::getKey, e -> e.getValue() + 256)
			);
			Map<KitType, Integer> equipSwaps = new HashMap<>(kitEquipSwaps);
			equipSwaps.putAll(itemEquipSwaps);

			SwapDiff equips = CompoundSwap.fromMap(equipSwaps).stream()
				.map(c -> this.swap(c, SwapMode.SAVE))
				.reduce(SwapDiff::mergeOver)
				.orElse(SwapDiff.blank());
			swapDiffHistory.appendToUndo(equips);
		});
	}

	// this should only be called from the client thread
	public List<String> stringifySwaps()
	{
		List<String> items = savedSwaps.itemEntries().stream()
			.map(e -> {
				KitType slot = e.getKey();
				Integer itemId = e.getValue();
				String itemName = itemManager.getItemComposition(itemId).getName();
				return slot.name() + ":" + itemId + " (" + itemName + ")";
			})
			.collect(Collectors.toList());
		List<String> kits = savedSwaps.kitEntries().stream()
			.map(e -> {
				KitType slot = e.getKey();
				Integer kitId = e.getValue();
				String kitName = KIT_MAP.get(kitId).getDisplayName();
				return slot.name() + KIT_SUFFIX + ":" + kitId + " (" + kitName + ")";
			})
			.collect(Collectors.toList());
		List<String> colors = savedSwaps.colorEntries().stream()
			.map(e -> {
				ColorType type = e.getKey();
				Integer colorId = e.getValue();
				return Arrays.stream(type.getColorables())
					.filter(c -> c.getColorId(type) == colorId)
					.findFirst()
					.map(c -> type.name() + COLOR_SUFFIX + ":" + colorId + " (" + c.getDisplayName() + ")")
					.orElse("");
			})
			.collect(Collectors.toList());
		items.addAll(kits);
		items.addAll(colors);
		return items;
	}

	@Nullable
	public Integer swappedItemIdIn(KitType slot)
	{
		return savedSwaps.getItem(slot);
	}

	@Nullable
	public Integer swappedKitIdIn(KitType slot)
	{
		return savedSwaps.getKit(slot);
	}

	@Nullable
	public Integer swappedColorIdIn(ColorType type)
	{
		return savedSwaps.getColor(type);
	}

	public Map<ColorType, Colorable> swappedColorsMap()
	{
		BiFunction<ColorType, Integer, Colorable> getColor = (type, id) -> {
			switch (type)
			{
				case HAIR:
					return HairColor.fromId(id);
				case TORSO:
					return ClothingColor.fromTorsoId(id);
				case LEGS:
					return ClothingColor.fromLegsId(id);
				case BOOTS:
					return BootsColor.fromId(id);
				case SKIN:
					return SkinColor.fromId(id);
			}
			return null;
		};
		return Arrays.stream(ColorType.values())
			.filter(savedSwaps::containsColor)
			.collect(Collectors.toMap(t -> t, t -> getColor.apply(t, savedSwaps.getColor(t))));
	}

	// this should only be called from the client thread
	public void revert(KitType slot)
	{
		SwapDiff s = SwapDiff.blank();
		if (slot != null)
		{
			savedSwaps.removeSlotLock(slot);
			s = s.mergeOver(doRevert(slot));
		}
		swapDiffHistory.appendToUndo(s);
	}

	// this should only be called from the client thread
	public void revertSlot(KitType slot)
	{
		savedSwaps.removeSlotLock(slot);
		SwapDiff s = doRevert(slot);
		swapDiffHistory.appendToUndo(s);
	}

	public void hoverOverItem(KitType slot, Integer itemId)
	{
		hoverOver(() -> swapItem(slot, itemId, false));
	}

	public void hoverOverKit(KitType slot, Integer kitId)
	{
		hoverOver(() -> swapKit(slot, kitId, false));
	}

	private void hoverOver(Supplier<SwapDiff> diffCallable)
	{
		clientThread.invokeLater(() -> {
			SwapDiff swapDiff = diffCallable.get();
			if (hoverSwapDiff == null)
			{
				hoverSwapDiff = swapDiff;
			}
			else if (!swapDiff.isBlank())
			{
				hoverSwapDiff = swapDiff.mergeOver(hoverSwapDiff);
			}
		});
	}

	public void hoverSelectItem(KitType slot, Integer itemId)
	{
		hoverSelect(() -> {
			if (savedSwaps.isItemLocked(slot))
			{
				return SwapDiff.blank();
			}
			return Objects.equals(savedSwaps.getItem(slot), itemId) ?
				doRevert(slot) :
				swapItem(slot, itemId, true);
		});
	}

	public void hoverSelectKit(KitType slot, Integer kitId)
	{
		hoverSelect(() -> {
			if (savedSwaps.isKitLocked(slot))
			{
				return SwapDiff.blank();
			}
			return Objects.equals(savedSwaps.getKit(slot), kitId) ?
				doRevert(slot) :
				swapKit(slot, kitId, true);
		});
	}

	private void hoverSelect(Supplier<SwapDiff> diffSupplier)
	{
		clientThread.invokeLater(() -> {
			SwapDiff swapDiff = diffSupplier.get();
			if (!swapDiff.isBlank())
			{
				if (hoverSwapDiff != null)
				{
					swapDiff = hoverSwapDiff.mergeOver(swapDiff);
				}
				swapDiffHistory.appendToUndo(swapDiff);
				hoverSwapDiff = null;
			}
		});
	}

	public void hoverAway()
	{
		clientThread.invokeLater(() -> {
			if (hoverSwapDiff != null)
			{
				restore(hoverSwapDiff, false);
				hoverSwapDiff = null;
			}
			refreshAllSwaps();
		});
	}

	/**
	 * Reverts all item/kit slots and colors. Unless force is true, locked slots will remain.
	 * Can only be called from the client thread.
	 */
	public void revertSwaps(boolean force)
	{
		if (force)
		{
			savedSwaps.removeAllLocks();
		}
		Map<KitType, Integer> equipIdsToRevert = Arrays.stream(KitType.values())
			.filter(slot -> !savedSwaps.isSlotLocked(slot))
			.collect(Collectors.toMap(slot -> slot, slot -> {
				Integer itemId = equippedItemIdFor(slot);
				int kitId = realKitIds.getOrDefault(slot, getFallbackKitId(slot));
				return itemId != null && itemId != 0 ? itemId + 512 : kitId + 256;
			}));
		SwapDiff kitsDiff = CompoundSwap.fromMap(equipIdsToRevert).stream()
			.map(c -> this.swap(c, SwapMode.REVERT))
			.reduce(SwapDiff::mergeOver)
			.orElse(SwapDiff.blank());
		swapDiffHistory.appendToUndo(kitsDiff);
		savedSwaps.clear();
	}

	/**
	 * @return a default kit id for the player's gender, if known. For slots without default values, or when
	 * gender is unknown, returns -256 (i.e., equipment id 0)
	 */
	private int getFallbackKitId(KitType slot)
	{
		if (isFemale != null && slot != null)
		{
			Map<KitType, Integer> map = isFemale ? FALLBACK_FEMALE_KITS : FALLBACK_MALE_KITS;
			return map.getOrDefault(slot, -256);
		}
		else
		{
			return -256;
		}
	}

	@Nullable
	// this should only be called from the client thread
	public Integer slotIdFor(ItemComposition itemComposition)
	{
		ItemEquipmentStats equipStats = equipmentStatsFor(itemComposition.getId());
		if (equipStats != null)
		{
			return equipStats.getSlot();
		}
		return null;
	}

	public void loadImports(List<String> allLines)
	{
		Map<KitType, Integer> itemImports = new HashMap<>();
		Map<KitType, Integer> kitImports = new HashMap<>();
		Map<ColorType, Integer> colorImports = new HashMap<>();
		for (String line : allLines)
		{
			if (line.trim().isEmpty())
			{
				continue;
			}
			Matcher matcher = FashionscapePlugin.PROFILE_PATTERN.matcher(line);
			if (matcher.matches())
			{
				String slotStr = matcher.group(1);
				// could be item id, kit id, or color id
				int id = Integer.parseInt(matcher.group(2));
				KitType itemSlot = itemSlotMatch(slotStr);
				KitType kitSlot = kitSlotMatch(slotStr);
				ColorType colorType = colorSlotMatch(slotStr);
				if (itemSlot != null)
				{
					itemImports.put(itemSlot, id);
				}
				else if (kitSlot != null)
				{
					kitImports.put(kitSlot, id);
				}
				else if (colorType != null)
				{
					colorImports.put(colorType, id);
				}
				else
				{
					sendHighlightedMessage("Could not import line: " + line);
				}
			}
		}
		if (!itemImports.isEmpty() || !kitImports.isEmpty() || !colorImports.isEmpty())
		{
			importSwaps(itemImports, kitImports);
		}
	}

	public void exportSwaps(File selected)
	{
		clientThread.invokeLater(() -> {
			try (PrintWriter out = new PrintWriter(selected))
			{
				List<String> exports = stringifySwaps();
				for (String line : exports)
				{
					out.println(line);
				}
				sendHighlightedMessage("Outfit saved to " + selected.getName());
			}
			catch (FileNotFoundException e)
			{
				e.printStackTrace();
			}
		});
	}

	public void copyOutfit(PlayerComposition other)
	{
		int[] equipmentIds = other.getEquipmentIds();
		KitType[] slots = KitType.values();
		Map<KitType, Integer> itemImports = IntStream.range(0, Math.max(slots.length, equipmentIds.length))
			.boxed()
			.filter(i -> equipmentIds[i] >= 512)
			.collect(Collectors.toMap(i -> slots[i], i -> equipmentIds[i] - 512));
		// only import kits if their gender matches ours
		Map<KitType, Integer> kitImports = !Objects.equals(isFemale, other.isFemale()) ?
			new HashMap<>() :
			IntStream.range(0, Math.max(slots.length, equipmentIds.length)).boxed()
				.filter(i -> i > 0 && i < 512)
				.collect(Collectors.toMap(i -> slots[i], i -> equipmentIds[i] - 256));

		if (!itemImports.isEmpty() || !kitImports.isEmpty())
		{
			importSwaps(itemImports, kitImports);
		}
	}

	/**
	 * Randomizes items/kits/colors in unlocked slots.
	 * Can only be called from the client thread.
	 */
	public void shuffle()
	{
		final Random r = new Random();
		RandomizerIntelligence intelligence = config.randomizerIntelligence();
		int size = intelligence.getDepth();
		if (size > 1)
		{
			Map<KitType, Integer> lockedSwaps = Arrays.stream(KitType.values())
				.filter(s -> savedSwaps.isItemLocked(s) && savedSwaps.containsItem(s))
				.collect(Collectors.toMap(s -> s, savedSwaps::getItem));
			Map<ColorType, Colorable> lockedColors = swappedColorsMap().entrySet().stream()
				.filter(e -> savedSwaps.isColorLocked(e.getKey()) && savedSwaps.containsColor(e.getKey()))
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
			colorScorer.setPlayerInfo(lockedSwaps, lockedColors);
		}
		Map<KitType, Boolean> itemSlotsToRevert = Arrays.stream(KitType.values())
			.collect(Collectors.toMap(slot -> slot, savedSwaps::isItemLocked));

		// pre-fill slots that will be skipped with -1 as a placeholder
		Map<KitType, Integer> newSwaps = Arrays.stream(KitType.values())
			.filter(itemSlotsToRevert::get)
			.collect(Collectors.toMap(s -> s, s -> -1));
		Set<Integer> skips = FashionscapePlugin.getItemIdsToExclude(config);
		List<Candidate> candidates = new ArrayList<>(size);
		List<Integer> randomOrder = IntStream.range(0, client.getItemCount()).boxed().collect(Collectors.toList());
		Collections.shuffle(randomOrder);
		Iterator<Integer> randomIterator = randomOrder.iterator();
		while (newSwaps.size() < KitType.values().length && randomIterator.hasNext())
		{
			Integer i = randomIterator.next();
			int canonical = itemManager.canonicalize(i);
			if (skips.contains(canonical))
			{
				continue;
			}
			ItemComposition itemComposition = itemManager.getItemComposition(canonical);
			int itemId = itemComposition.getId();
			KitType slot = slotForId(slotIdFor(itemComposition));
			if (slot != null && !newSwaps.containsKey(slot))
			{
				// Don't equip a 2h weapon if we already have a shield
				if (slot == KitType.WEAPON)
				{
					ItemEquipmentStats stats = equipmentStatsFor(itemId);
					if (stats != null && stats.isTwoHanded() && newSwaps.get(KitType.SHIELD) != null)
					{
						continue;
					}
				}
				// Don't equip a shield if we already have a 2h weapon (mark shield as removed instead)
				else if (slot == KitType.SHIELD)
				{
					Integer weaponItemId = newSwaps.get(KitType.WEAPON);
					if (weaponItemId != null)
					{
						ItemEquipmentStats stats = equipmentStatsFor(weaponItemId);
						if (stats != null && stats.isTwoHanded())
						{
							newSwaps.put(KitType.SHIELD, -1);
							continue;
						}
					}
				}
				else if (slot == KitType.HEAD)
				{
					// Don't equip a helm if it hides hair and hair is locked
					if (!ItemInteractions.HAIR_HELMS.contains(itemId) && savedSwaps.isKitLocked(KitType.HAIR))
					{
						continue;
					}
					// Don't equip a helm if it hides jaw and jaw is locked
					if (ItemInteractions.NO_JAW_HELMS.contains(itemId) && savedSwaps.isKitLocked(KitType.JAW))
					{
						continue;
					}
				}
				else if (slot == KitType.TORSO)
				{
					// Don't equip torso if it hides arms and arms is locked
					if (!ItemInteractions.ARMS_TORSOS.contains(itemId) && savedSwaps.isKitLocked(KitType.ARMS))
					{
						continue;
					}
				}
				candidates.add(new Candidate(itemComposition.getId(), slot));
			}

			if (!candidates.isEmpty() && candidates.size() >= size)
			{
				Candidate best;
				if (size > 1)
				{
					best = candidates.stream()
						.max(Comparator.comparingDouble(c -> colorScorer.score(c.itemId, c.slot)))
						.get();
					colorScorer.addPlayerInfo(best.slot, best.itemId);
				}
				else
				{
					best = candidates.get(0);
				}
				newSwaps.put(best.slot, best.itemId);
				candidates.clear();
			}
		}
		// slots filled with -1 were placeholders that need to be removed
		List<KitType> removes = newSwaps.entrySet().stream()
			.filter(e -> e.getValue() < 0)
			.map(Map.Entry::getKey)
			.collect(Collectors.toList());
		removes.forEach(newSwaps::remove);

		// shuffle colors
		Map<ColorType, Integer> newColors = new HashMap<>();
		List<ColorType> allColorTypes = Arrays.asList(ColorType.values().clone());
		Collections.shuffle(allColorTypes);
		for (ColorType type : allColorTypes)
		{
			if (savedSwaps.isColorLocked(type))
			{
				continue;
			}
			List<Colorable> colorables = Arrays.asList(type.getColorables().clone());
			if (colorables.isEmpty())
			{
				continue;
			}
			Collections.shuffle(colorables);
			int limit;
			switch (intelligence)
			{
				case LOW:
					limit = Math.max(1, colorables.size() / 4);
					break;
				case MODERATE:
					limit = Math.max(1, colorables.size() / 2);
					break;
				case HIGH:
					limit = colorables.size();
					break;
				default:
					limit = 1;
			}
			Colorable best = colorables.stream()
				.limit(limit)
				.max(Comparator.comparingDouble(c -> colorScorer.score(c, type)))
				.orElse(colorables.get(0));
			colorScorer.addPlayerColor(type, best);
			newColors.put(type, best.getColorId(type));
		}

		SwapDiff totalDiff = SwapDiff.blank();

		// convert to equipment ids
		Map<KitType, Integer> newEquipSwaps = newSwaps.entrySet().stream()
			.collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue() + 512));

		// swap items now before moving on to kits
		SwapDiff itemsDiff = CompoundSwap.fromMap(newEquipSwaps).stream()
			.map(c -> this.swap(c, SwapMode.SAVE))
			.reduce(SwapDiff::mergeOver)
			.orElse(SwapDiff.blank());
		totalDiff = totalDiff.mergeOver(itemsDiff);

		// See if remaining slots can be kit-swapped
		if (isFemale != null && !config.excludeBaseModels())
		{
			Map<KitType, Integer> kitSwaps = Arrays.stream(KitType.values())
				.filter(slot -> !newSwaps.containsKey(slot) && isOpen(slot))
				.map(slot -> {
					List<Kit> kits = ALL_KITS_MAP.getOrDefault(slot, new ArrayList<>()).stream()
						.filter(k -> k.isFemale() == isFemale)
						.collect(Collectors.toList());
					return kits.isEmpty() ? null : kits.get(r.nextInt(kits.size()));
				})
				.filter(Objects::nonNull)
				.collect(Collectors.toMap(Kit::getKitType, Kit::getKitId));

			Map<KitType, Integer> kitEquipSwaps = kitSwaps.entrySet().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue() + 256));

			SwapDiff kitsDiff = CompoundSwap.fromMap(kitEquipSwaps)
				.stream()
				.map(c -> this.swap(c, SwapMode.SAVE))
				.reduce(SwapDiff::mergeOver)
				.orElse(SwapDiff.blank());

			totalDiff = totalDiff.mergeOver(kitsDiff);
		}

		swapDiffHistory.appendToUndo(totalDiff);
	}

	// this should only be called from the client thread
	public SwapDiff swapItem(KitType slot, Integer itemId, boolean save)
	{
		if (itemId == null)
		{
			return SwapDiff.blank();
		}
		int equipmentId = itemId + 512;
		SwapMode swapMode = save ? SwapMode.SAVE : SwapMode.PREVIEW;
		SwapDiff swapDiff = swap(CompoundSwap.single(slot, equipmentId), swapMode);
		if (hoverSwapDiff != null)
		{
			swapDiff = swapDiff.mergeOver(hoverSwapDiff);
		}
		return swapDiff;
	}

	// this should only be called from the client thread
	public SwapDiff swapKit(KitType slot, Integer kitId, boolean save)
	{
		if (kitId == null)
		{
			return SwapDiff.blank();
		}
		int equipmentId = kitId + 256;
		SwapMode swapMode = save ? SwapMode.SAVE : SwapMode.PREVIEW;
		SwapDiff swapDiff = swap(CompoundSwap.single(slot, equipmentId), swapMode);
		if (hoverSwapDiff != null)
		{
			swapDiff = swapDiff.mergeOver(hoverSwapDiff);
		}
		return swapDiff;
	}

	/**
	 * Most of the requested swaps should call this method instead of the more specific swaps below, to ensure
	 * that the swap doesn't result in illegal combinations of models
	 */
	private SwapDiff swap(CompoundSwap s, SwapMode swapMode)
	{
		return swap(s, (ignore) -> swapMode);
	}

	private SwapDiff swap(CompoundSwap s, Function<KitType, SwapMode> swapModeProvider)
	{
		switch (s.getType())
		{
			case HEAD:
				Integer headEquipId = s.getEquipmentIds().get(KitType.HEAD);
				Integer hairEquipId = s.getEquipmentIds().get(KitType.HAIR);
				Integer jawEquipId = s.getEquipmentIds().get(KitType.JAW);
				return swapHead(headEquipId, hairEquipId, jawEquipId, swapModeProvider);
			case TORSO:
				Integer torsoEquipId = s.getEquipmentIds().get(KitType.TORSO);
				Integer armsEquipId = s.getEquipmentIds().get(KitType.ARMS);
				return swapTorso(torsoEquipId, armsEquipId, swapModeProvider);
			case WEAPONS:
				Integer weaponEquipId = s.getEquipmentIds().get(KitType.WEAPON);
				Integer shieldEquipId = s.getEquipmentIds().get(KitType.SHIELD);
				return swapWeapons(weaponEquipId, shieldEquipId, swapModeProvider);
			case SINGLE:
				return swapSingle(s.getKitType(), s.getEquipmentId(), swapModeProvider.apply(s.getKitType()));
		}
		return SwapDiff.blank();
	}

	private SwapDiff swapSingle(KitType slot, Integer equipmentId, SwapMode swapMode)
	{
		SwapDiff.Change result = swap(slot, equipmentId, swapMode);
		Map<KitType, SwapDiff.Change> changes = new HashMap<>();
		if (result != null)
		{
			changes.put(slot, result);
		}
		return new SwapDiff(changes, new HashMap<>(), null);
	}

	/**
	 * Swaps an item in the head slot, and kits in the hair and/or jaw slots. Behavior changes depending
	 * on the information present:
	 * <p>
	 * When the head slot id is null, the head slot will not change. The hair and jaw kits sent
	 * will be used if the currently shown item allows for it, otherwise nothing will happen.
	 * <p>
	 * The head slot item is removed when id == 0. This means swapping to the hair and jaw kit ids
	 * if they are sent, otherwise the player's existing kit ids will be used.
	 * <p>
	 * For non-null, non-zero head ids, the head item will be swapped, but not if (the item hides hair and the hair kit
	 * is locked) or (the item hides jaws and the jaw slot is locked). If the new item allows showing hair
	 * and/or jaw kits, then they will also be swapped using the sent values or the existing kits on the player.
	 * If the kits are not allowed, they will be hidden as needed.
	 */
	private SwapDiff swapHead(
		Integer headEquipId,
		Integer hairEquipId,
		Integer jawEquipId,
		Function<KitType, SwapMode> swapModeProvider)
	{
		// equipment ids that will be used in the swap
		Integer finalHeadId = null;
		Integer finalHairId;
		Integer finalJawId;

		Function<Integer, Boolean> headAllowsHair = (equipId) ->
			equipId < 512 || ItemInteractions.HAIR_HELMS.contains(equipId - 512);
		Function<Integer, Boolean> headAllowsJaw = (equipId) ->
			equipId < 512 || !ItemInteractions.NO_JAW_HELMS.contains(equipId - 512);

		int currentHeadEquipId = equipmentIdInSlot(KitType.HEAD);
		if (savedSwaps.isItemLocked(KitType.HEAD))
		{
			// only change hair/jaw and only if current head item allows
			if (!savedSwaps.isKitLocked(KitType.HAIR) && hairEquipId != null)
			{
				boolean hairAllowed = (headAllowsHair.apply(currentHeadEquipId) && hairEquipId > 0) ||
					(!headAllowsHair.apply(currentHeadEquipId) && hairEquipId <= 0);
				finalHairId = hairAllowed ? hairEquipId : null;
			}
			else
			{
				finalHairId = null;
			}
			if (!savedSwaps.isKitLocked(KitType.JAW) && jawEquipId != null)
			{
				boolean jawAllowed = (headAllowsJaw.apply(currentHeadEquipId) && jawEquipId > 0) ||
					(!headAllowsJaw.apply(currentHeadEquipId) && jawEquipId <= 0);
				finalJawId = jawAllowed ? jawEquipId : null;
			}
			else
			{
				finalJawId = null;
			}
		}
		else if (headEquipId == null)
		{
			// prioritize showing hair/jaw and hiding current helm if new kits conflict with it.
			finalHairId = !savedSwaps.isKitLocked(KitType.HAIR) ? hairEquipId : null;
			finalJawId = !savedSwaps.isKitLocked(KitType.JAW) ? jawEquipId : null;
			boolean headForbidsHair = !headAllowsHair.apply(currentHeadEquipId) && finalHairId != null &&
				finalHairId > 0;
			boolean headForbidsJaw = !headAllowsJaw.apply(currentHeadEquipId) && finalJawId != null &&
				finalJawId > 0;
			finalHeadId = headForbidsHair || headForbidsJaw ? 0 : null;
		}
		else
		{
			// priority: show head and hide hair/jaw, but not if locks on hair/jaw disallow (in which case don't change)
			boolean hairDisallowed = !headAllowsHair.apply(headEquipId) && savedSwaps.isKitLocked(KitType.HAIR);
			boolean jawDisallowed = !headAllowsJaw.apply(headEquipId) && savedSwaps.isKitLocked(KitType.JAW);
			finalHeadId = hairDisallowed || jawDisallowed ? null : headEquipId;
			Integer headIdToCheck = hairDisallowed || jawDisallowed ? currentHeadEquipId : headEquipId;
			Integer potentialHairId = headAllowsHair.apply(headIdToCheck) ? hairEquipId : Integer.valueOf(0);
			finalHairId = !savedSwaps.isKitLocked(KitType.HAIR) ? potentialHairId : null;
			Integer potentialJawId = headAllowsJaw.apply(headIdToCheck) ? jawEquipId : Integer.valueOf(0);
			finalJawId = !savedSwaps.isKitLocked(KitType.JAW) ? potentialJawId : null;
		}

		Map<KitType, SwapDiff.Change> changes = new HashMap<>();

		// edge cases:
		// if not changing hair but hair can be shown, make sure that at least something is displayed there
		if (finalHairId == null && equipmentIdInSlot(KitType.HAIR) == 0 &&
			((finalHeadId == null && headAllowsHair.apply(currentHeadEquipId)) ||
				(finalHeadId != null && headAllowsHair.apply(finalHeadId))))
		{
			if (savedSwaps.isKitLocked(KitType.HAIR))
			{
				// hair is locked on nothing, don't change helm to something that could show empty kit
				finalHeadId = null;
				finalJawId = null;
			}
			else
			{
				int revertHairId = realKitIds.getOrDefault(KitType.HAIR, getFallbackKitId(KitType.HAIR)) + 256;
				SwapDiff.Change result = swap(KitType.HAIR, revertHairId, SwapMode.REVERT);
				if (result != null)
				{
					changes.put(KitType.HAIR, result);
				}
			}
		}
		// if not changing jaw but jaw can be shown, make sure that at least something is displayed there
		if (finalJawId == null && equipmentIdInSlot(KitType.JAW) == 0 &&
			((finalHeadId == null && headAllowsJaw.apply(currentHeadEquipId)) ||
				(finalHeadId != null && headAllowsJaw.apply(finalHeadId))))
		{
			if (savedSwaps.isKitLocked(KitType.JAW))
			{
				// jaw is locked on nothing, don't change helm to something that could show empty kit
				finalHeadId = null;
				finalHairId = null;
			}
			else
			{
				int revertJawId = realKitIds.getOrDefault(KitType.JAW, getFallbackKitId(KitType.JAW)) + 256;
				SwapDiff.Change result = swap(KitType.JAW, revertJawId, SwapMode.REVERT);
				if (result != null)
				{
					changes.put(KitType.JAW, result);
				}
			}
		}

		BiConsumer<KitType, Integer> attemptChange = (slot, equipId) -> {
			if (equipId != null && equipId >= 0)
			{
				SwapDiff.Change result = swap(slot, equipId, swapModeProvider.apply(slot));
				if (result != null)
				{
					changes.put(slot, result);
				}
			}
		};
		attemptChange.accept(KitType.HEAD, finalHeadId);
		attemptChange.accept(KitType.HAIR, finalHairId);
		attemptChange.accept(KitType.JAW, finalJawId);
		return new SwapDiff(changes, new HashMap<>(), null);
	}

	/**
	 * Swaps an item or kit in the torso slot and a kit in the arms slot. Behavior changes depending
	 * on the information present:
	 * <p>
	 * When the torso slot id is null, the torso slot will not change. The arms kits sen will be
	 * used if the currently shown torso allows for it, otherwise nothing will happen.
	 * <p>
	 * The torso slot item is removed when id == 0. This means swapping to the arms kit id if it is sent,
	 * otherwise the player's existing kit id will be used.
	 * <p>
	 * For non-null, non-zero torso ids, the torso slot item will change, but not if the item hides arms and the arms
	 * kit is currently locked. If the new item allows showing the arms kit, then it will also be swapped using the
	 * sent value or the existing kit on the player. If the torso does not allow showing arms, then the arms kit
	 * will be removed.
	 */
	private SwapDiff swapTorso(
		Integer torsoEquipId,
		Integer armsEquipId,
		Function<KitType, SwapMode> swapModeProvider)
	{
		Integer finalTorsoId = null;
		Integer finalArmsId;

		Function<Integer, Boolean> torsoAllowsArms = (equipId) ->
			equipId < 512 || ItemInteractions.ARMS_TORSOS.contains(equipId - 512);

		Map<KitType, SwapDiff.Change> changes = new HashMap<>();

		int currentTorsoEquipId = equipmentIdInSlot(KitType.TORSO);
		if (savedSwaps.isKitLocked(KitType.TORSO) ||
			(savedSwaps.isItemLocked(KitType.TORSO) && torsoEquipId != null && torsoEquipId >= 512))
		{
			// only change arms and only if current torso item allows
			if (savedSwaps.isKitLocked(KitType.ARMS) && armsEquipId != null)
			{
				boolean armsAllowed = (torsoAllowsArms.apply(currentTorsoEquipId) && armsEquipId > 0) ||
					(!torsoAllowsArms.apply(currentTorsoEquipId) && armsEquipId <= 0);
				finalArmsId = armsAllowed ? armsEquipId : null;
			}
			else
			{
				finalArmsId = null;
			}
		}
		else if (torsoEquipId == null)
		{
			// prioritize showing arms and hiding current torso if applicable
			finalArmsId = !savedSwaps.isKitLocked(KitType.ARMS) ? armsEquipId : null;
			boolean torsoForbidsArms = !torsoAllowsArms.apply(currentTorsoEquipId) && finalArmsId != null &&
				finalArmsId > 0;
			if (torsoForbidsArms)
			{
				int revertTorsoId = realKitIds.getOrDefault(KitType.TORSO, getFallbackKitId(KitType.TORSO)) + 256;
				SwapDiff.Change result = swap(KitType.TORSO, revertTorsoId, SwapMode.REVERT);
				if (result != null)
				{
					changes.put(KitType.TORSO, result);
				}
			}
		}
		else
		{
			// priority: show torso and hide arms, but not if locks on arms disallow (in which case don't change)
			boolean torsoDisallowed = !torsoAllowsArms.apply(torsoEquipId) && savedSwaps.isKitLocked(KitType.ARMS);
			finalTorsoId = torsoDisallowed ? null : torsoEquipId;
			Integer torsoIdToCheck = torsoDisallowed ? currentTorsoEquipId : torsoEquipId;
			Integer potentialArmsId = torsoAllowsArms.apply(torsoIdToCheck) ? armsEquipId : Integer.valueOf(0);
			finalArmsId = !savedSwaps.isKitLocked(KitType.ARMS) ? potentialArmsId : null;
		}

		// edge case:
		// if not changing arms but arms can be shown, make sure that at least something is displayed there
		if (finalArmsId == null && equipmentIdInSlot(KitType.ARMS) == 0 &&
			((finalTorsoId == null && torsoAllowsArms.apply(currentTorsoEquipId)) ||
				(finalTorsoId != null && torsoAllowsArms.apply(finalTorsoId))))
		{
			if (savedSwaps.isKitLocked(KitType.ARMS))
			{
				// arms locked on nothing, don't change torso to something that could show empty kit
				finalTorsoId = null;
			}
			else
			{
				int revertArmsId = realKitIds.getOrDefault(KitType.ARMS, getFallbackKitId(KitType.ARMS)) + 256;
				SwapDiff.Change result = swap(KitType.ARMS, revertArmsId, SwapMode.REVERT);
				if (result != null)
				{
					changes.put(KitType.ARMS, result);
				}
			}
		}

		BiConsumer<KitType, Integer> attemptChange = (slot, equipId) -> {
			if (equipId != null && equipId >= 0)
			{
				SwapDiff.Change result = swap(slot, equipId, swapModeProvider.apply(slot));
				if (result != null)
				{
					changes.put(slot, result);
				}
			}
		};
		attemptChange.accept(KitType.TORSO, finalTorsoId);
		attemptChange.accept(KitType.ARMS, finalArmsId);
		return new SwapDiff(changes, new HashMap<>(), null);
	}

	/**
	 * Convenience method that returns the first non-null of: equipId, the current equipment id in this slot,
	 * or the fallback kit id if allowFallback.
	 */
	private int getEquipmentIdOrFallback(Integer equipId, KitType slot, boolean allowFallback)
	{
		if (equipId != null)
		{
			return equipId;
		}
		int kitEquipId = equipmentIdForKit(slot);
		if (kitEquipId >= 256)
		{
			return kitEquipId;
		}
		if (allowFallback)
		{
			return getFallbackKitId(slot) + 256;
		}
		return -1;
	}

	/**
	 * Swaps an item weapon slot and an item in the shield slot. Behavior changes depending
	 * on the information present:
	 * <p>
	 * If the weapon id is null, only the shield will be considered. If the shield is non-null and
	 * non-zero, and the current weapon is two-handed, then the weapon will be removed.
	 * <p>
	 * If the weapon is non-null, non-zero, and two-handed, the shield will be removed. Otherwise,
	 * the weapon and shield are both swapped if they are respectively non-null.
	 * <p>
	 * Lastly, the idle animation ID will change if the sent weapon id is non-null or if it's determined
	 * that the weapon must be removed.
	 */
	private SwapDiff swapWeapons(
		Integer weaponEquipId,
		Integer shieldEquipId,
		Function<KitType, SwapMode> swapModeProvider)
	{
		Integer finalWeaponId = null;
		Integer finalShieldId;
		Integer finalAnimId = null;

		Function<Integer, Boolean> weaponForbidsShields = (equipId) -> {
			if (equipId >= 512)
			{
				ItemEquipmentStats stats = equipmentStatsFor(equipId - 512);
				return stats != null && stats.isTwoHanded();
			}
			return false;
		};

		if (weaponEquipId == null || savedSwaps.isItemLocked(KitType.WEAPON))
		{
			finalShieldId = shieldEquipId;
			if (shieldEquipId != null && shieldEquipId > 0 &&
				weaponForbidsShields.apply(equipmentIdInSlot(KitType.WEAPON)))
			{
				if (!savedSwaps.isItemLocked(KitType.WEAPON))
				{
					finalWeaponId = 0;
					finalAnimId = IdleAnimationID.DEFAULT;
				}
				else
				{
					// weapon is locked and 2h, should not equip shield
					finalShieldId = null;
				}
			}
		}
		else
		{
			finalShieldId = weaponForbidsShields.apply(weaponEquipId) && !savedSwaps.isItemLocked(KitType.SHIELD) ?
				Integer.valueOf(0) :
				shieldEquipId;
			finalWeaponId = weaponForbidsShields.apply(weaponEquipId) && savedSwaps.isItemLocked(KitType.SHIELD) ?
				null :
				weaponEquipId;
			finalAnimId = finalWeaponId != null ?
				ItemInteractions.WEAPON_TO_IDLE.getOrDefault(weaponEquipId - 512, IdleAnimationID.DEFAULT) :
				null;
		}

		Map<KitType, SwapDiff.Change> changes = new HashMap<>();
		BiConsumer<KitType, Integer> attemptChange = (slot, equipId) -> {
			if (equipId != null && equipId >= 0)
			{
				SwapDiff.Change result = swap(slot, equipId, swapModeProvider.apply(slot));
				if (result != null)
				{
					changes.put(slot, result);
				}
			}
		};
		attemptChange.accept(KitType.WEAPON, finalWeaponId);
		attemptChange.accept(KitType.SHIELD, finalShieldId);

		Integer changedAnim = null;
		if (finalAnimId != null && finalAnimId >= 0)
		{
			Player player = client.getLocalPlayer();
			if (player != null)
			{
				changedAnim = setIdleAnimationId(player, finalAnimId);
				if (changedAnim.equals(finalAnimId))
				{
					changedAnim = null;
				}
			}
		}
		return new SwapDiff(changes, new HashMap<>(), changedAnim);
	}

	/**
	 * this should only be called from one of the swap methods utilizing `CompoundSwap`, otherwise the
	 * swap may not make logical sense.
	 * <p>
	 * equipmentId follows `PlayerComposition::getEquipmentId`:
	 * 0 for nothing
	 * 256-511 for a base kit model (i.e., kitId + 256)
	 * >=512 for an item (i.e., itemId + 512)
	 * <p>
	 * save should only be false when previewing. forceClear should be true when reverting
	 *
	 * @return the previously occupied equipment id and whether the change was natural if the equipment has
	 * successfully been changed, otherwise null
	 */
	@Nullable
	private SwapDiff.Change swap(KitType slot, int equipmentId, SwapMode swapMode)
	{
		if (slot == null)
		{
			return null;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			return null;
		}
		int oldId = setEquipmentId(composition, slot, equipmentId);
		boolean unnatural = savedSwaps.containsSlot(slot);
		switch (swapMode)
		{
			case SAVE:
				if (equipmentId <= 0)
				{
					savedSwaps.removeSlot(slot);
				}
				else if (equipmentId < 512)
				{
					savedSwaps.putKit(slot, equipmentId - 256);
				}
				else
				{
					savedSwaps.putItem(slot, equipmentId - 512);
				}
				break;
			case REVERT:
				savedSwaps.removeSlot(slot);
				break;
			case PREVIEW:
				break;
		}
		return oldId == equipmentId ? null : new SwapDiff.Change(oldId, unnatural);
	}

	// Sets equipment id for slot and returns equipment id of previously occupied item.
	private int setEquipmentId(@Nonnull PlayerComposition composition, @Nonnull KitType slot, int equipmentId)
	{
		int previousId = composition.getEquipmentIds()[slot.getIndex()];
		composition.getEquipmentIds()[slot.getIndex()] = equipmentId;
		composition.setHash();
		return previousId;
	}

	// Sets idle animation id for current player and returns previous idle animation
	private int setIdleAnimationId(@Nonnull Player player, int animationId)
	{
		int previousId = player.getIdlePoseAnimation();
		player.setIdlePoseAnimation(animationId);
		return previousId;
	}

	/**
	 * Performs a revert back to an "original" state for the given slot.
	 * In most cases, this swaps to whatever was actually equipped in the slot. Some exceptions:
	 * When reverting a kit, and an item is actually equipped in a slot that hides that kit, the item will be shown
	 * and the kit will be hidden.
	 */
	private SwapDiff doRevert(KitType slot)
	{
//		if (slot == KitType.HAIR)
//		{
//			Integer headEquipId = equippedItemIdFor(KitType.HEAD);
//			if (headEquipId != null && headEquipId >= 512 && !ItemInteractions.HAIR_HELMS.contains(headEquipId - 512))
//			{
//				return swap(CompoundSwap.single(KitType.HEAD, headEquipId), SwapMode.REVERT);
//			}
//		}
//		else if (slot == KitType.JAW)
//		{
//			Integer headEquipId = equippedItemIdFor(KitType.HEAD);
//			if (headEquipId != null && headEquipId >= 512 && ItemInteractions.NO_JAW_HELMS.contains(headEquipId - 512))
//			{
//				return swap(CompoundSwap.single(KitType.HEAD, headEquipId), SwapMode.REVERT);
//			}
//		}
//		else if (slot == KitType.ARMS)
//		{
//			Integer torsoEquipId = equippedItemIdFor(KitType.TORSO);
//			if (torsoEquipId != null && torsoEquipId >= 512 && !ItemInteractions.ARMS_TORSOS.contains(torsoEquipId - 512))
//			{
//				return swap(CompoundSwap.single(KitType.TORSO, torsoEquipId), SwapMode.REVERT);
//			}
//		}
		Integer originalItemId = equippedItemIdFor(slot);
		Integer originalKitId = realKitIds.getOrDefault(slot, getFallbackKitId(slot));
		if (originalKitId == -256)
		{
			originalKitId = null;
		}
		int equipmentId;
		if (originalItemId != null)
		{
			equipmentId = originalItemId < 0 ? 0 : originalItemId + 512;
		}
		else if (originalKitId != null)
		{
			equipmentId = originalKitId < 0 ? 0 : originalKitId + 256;
		}
		else
		{
			equipmentId = 0;
		}
		return swap(CompoundSwap.single(slot, equipmentId), SwapMode.REVERT);
	}

	@Nullable
	private KitType slotForId(Integer slotId)
	{
		if (slotId == null)
		{
			return null;
		}
		return Arrays.stream(KitType.values())
			.filter(type -> type.getIndex() == slotId)
			.findFirst()
			.orElse(null);
	}

	/**
	 * @return true if the slot does not have an item (real or virtual) obscuring it (directly or indirectly)
	 */
	private boolean isOpen(KitType slot)
	{
		if (slot == null || savedSwaps.isKitLocked(slot) || savedSwaps.containsItem(slot))
		{
			return false;
		}
		Supplier<Boolean> fallback = () -> {
			if (equipmentIdInSlot(slot) > 0)
			{
				// equipment id must be a kit since there can't be an item at this point
				return true;
			}
			else
			{
				Integer actualEquipId = equippedItemIdFor(slot);
				return actualEquipId == null || actualEquipId < 512;
			}
		};
		switch (slot)
		{
			case HEAD:
			case JAW:
			case HAIR:
				int headEquipId = equipmentIdInSlot(KitType.HEAD);
				if (headEquipId == 0)
				{
					Integer actualEquipId = equippedItemIdFor(KitType.HEAD);
					headEquipId = actualEquipId != null ? actualEquipId : 0;
				}
				if (headEquipId > 512)
				{
					int headItemId = headEquipId - 512;
					if (slot == KitType.HAIR)
					{
						return ItemInteractions.HAIR_HELMS.contains(headItemId);
					}
					else if (slot == KitType.JAW)
					{
						return !ItemInteractions.NO_JAW_HELMS.contains(headItemId);
					}
				}
				return fallback.get();
			case TORSO:
			case ARMS:
				int torsoEquipId = equipmentIdInSlot(KitType.TORSO);
				if (torsoEquipId == 0)
				{
					Integer actualEquipId = equippedItemIdFor(KitType.TORSO);
					torsoEquipId = actualEquipId != null ? actualEquipId : 0;
				}
				if (torsoEquipId > 512)
				{
					int torsoItemId = torsoEquipId - 512;
					if (slot == KitType.ARMS)
					{
						return ItemInteractions.ARMS_TORSOS.contains(torsoItemId);
					}
				}
				return fallback.get();
			default:
				return fallback.get();
		}
	}

	@Nullable
	private ItemEquipmentStats equipmentStatsFor(int itemId)
	{
		ItemStats stats = itemManager.getItemStats(itemId, false);
		return stats != null && stats.isEquipable() ? stats.getEquipment() : null;
	}

	/**
	 * returns the equipment id of whatever is being displayed in the given slot
	 */
	private int equipmentIdInSlot(KitType kitType)
	{
		Integer itemId = savedSwaps.getItemOrDefault(kitType, equippedItemIdFor(kitType));
		if (itemId != null && itemId >= 0)
		{
			return itemId + 512;
		}
		Integer kitId = kitIdFor(kitType);
		return kitId != null && kitId >= 0 ? kitId + 256 : 0;
	}

	/**
	 * Returns the equipment id of whatever kit is currently in this slot, bypassing the equipment id of the item in
	 * that slot. The id could be that of a swapped kit or the player's actual kit. There is no guarantee that
	 * the returned kit id is currently being shown.
	 */
	private int equipmentIdForKit(KitType slot)
	{
		return savedSwaps.getKitOrDefault(slot, realKitIds.getOrDefault(slot, -256)) + 256;
	}

	/**
	 * returns the item id of the actual item equipped in the given slot (swaps are ignored)
	 */
	@Nullable
	public Integer equippedItemIdFor(KitType kitType)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}
		PlayerComposition composition = player.getPlayerComposition();
		if (composition == null)
		{
			return null;
		}
		ItemContainer inventory = client.getItemContainer(InventoryID.EQUIPMENT);
		if (inventory == null)
		{
			return null;
		}
		Item item = inventory.getItem(kitType.getIndex());
		return item != null && item.getId() >= 0 ? item.getId() : null;
	}

	@Nullable
	private Integer kitIdFor(KitType kitType)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return null;
		}
		PlayerComposition composition = player.getPlayerComposition();
		return composition == null ? null : composition.getKitId(kitType);
	}

	// restores diff and returns a new diff with reverted changes (allows redo)
	private SwapDiff restore(SwapDiff swapDiff, boolean save)
	{
		Function<KitType, SwapMode> swapModeProvider = (slot) -> {
			SwapDiff.Change change = swapDiff.getSlotChanges().get(slot);
			return !save ? SwapMode.PREVIEW : change != null && change.isUnnatural() ?
				SwapMode.SAVE :
				SwapMode.REVERT;
		};
		// restore kits and items
		return CompoundSwap.fromMap(
			swapDiff.getSlotChanges().entrySet().stream().collect(
				Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getId())))
			.stream()
			.map(c -> this.swap(c, swapModeProvider))
			.reduce(SwapDiff::mergeOver)
			.orElse(SwapDiff.blank());
	}

	@Nullable
	private KitType itemSlotMatch(String name)
	{
		try
		{
			return KitType.valueOf(name);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	@Nullable
	private KitType kitSlotMatch(String name)
	{
		try
		{
			String k = name.replace(KIT_SUFFIX, "");
			return KitType.valueOf(k);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	@Nullable
	private ColorType colorSlotMatch(String name)
	{
		try
		{
			String c = name.replace(COLOR_SUFFIX, "");
			return ColorType.valueOf(c);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private void sendHighlightedMessage(String message)
	{
		String chatMessage = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append(message)
			.build();

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(chatMessage)
			.build());
	}

}
