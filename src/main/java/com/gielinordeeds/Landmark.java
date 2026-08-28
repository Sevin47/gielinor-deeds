/*
 * Copyright (c) 2026, Sevin
 * All rights reserved.
 * SPDX-License-Identifier: BSD-2-Clause
 */
package com.gielinordeeds;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Famous places worth owning for their own sake.
 *
 * These are the Deed Log's trophy shelf. The Log lists every one by name from
 * the start, filled in as you claim them, and never says where
 * any of them is. Every OSRS player already knows where Camelot is; leaning on
 * that is far better flavour than a coordinate hint, and it keeps the map itself
 * dark until you walk it.
 *
 * Every entry below was checked against the survey: all 66 resolve to claimable
 * land in the kingdom a player would name. Three waterside entries (Port Sarim,
 * Port Piscarilius, the Fishing Guild) sit slightly inland of the buildings
 * themselves, because at 8x8 granularity the parcel under the dock is majority
 * water. A landmark is always claimable regardless, but one sitting on a water
 * parcel would show as Open water in the Deed Log, which reads as a bug.
 */
@Getter
@RequiredArgsConstructor
public enum Landmark
{
	// Misthalin
	LUMBRIDGE_CASTLE("Lumbridge Castle", 3222, 3218),
	VARROCK_SQUARE("Varrock Square", 3213, 3428),
	GRAND_EXCHANGE("Grand Exchange", 3165, 3487),
	VARROCK_PALACE("Varrock Palace", 3213, 3472),
	DRAYNOR_MANOR("Draynor Manor", 3108, 3353),
	DRAYNOR_VILLAGE("Draynor Village", 3093, 3244),
	EDGEVILLE("Edgeville", 3087, 3496),
	BARBARIAN_VILLAGE("Barbarian Village", 3082, 3420),
	AL_KHARID_PALACE("Al Kharid Palace", 3293, 3167),
	WIZARDS_TOWER("Wizards' Tower", 3109, 3170),
	CHAMPIONS_GUILD("Champions' Guild", 3191, 3363),

	// Asgarnia
	FALADOR_CENTRE("Falador Centre", 2965, 3378),
	WHITE_KNIGHTS_CASTLE("White Knights' Castle", 2977, 3341),
	FALADOR_PARTY_ROOM("Falador Party Room", 3047, 3376),
	TAVERLEY("Taverley", 2894, 3428),
	BURTHORPE("Burthorpe", 2899, 3545),
	RIMMINGTON("Rimmington", 2957, 3213),
	PORT_SARIM("Port Sarim", 3021, 3205),
	DWARVEN_MINE("Dwarven Mine", 3020, 3340),

	// Kandarin
	CAMELOT_CASTLE("Camelot Castle", 2757, 3477),
	SEERS_VILLAGE("Seers' Village", 2704, 3488),
	CATHERBY("Catherby", 2809, 3435),
	ARDOUGNE_MARKET("Ardougne Market", 2660, 3305),
	YANILLE("Yanille", 2605, 3093),
	FISHING_GUILD("Fishing Guild", 2596, 3420),
	LEGENDS_GUILD("Legends' Guild", 2729, 3348),
	BAXTORIAN_FALLS("Baxtorian Falls", 2520, 3475),
	TREE_GNOME_STRONGHOLD("Tree Gnome Stronghold", 2461, 3444),
	RANGING_GUILD("Ranging Guild", 2658, 3439),
	CASTLE_WARS("Castle Wars", 2440, 3090),

	// Morytania
	CANIFIS("Canifis", 3495, 3490),
	PORT_PHASMATYS("Port Phasmatys", 3685, 3475),
	BARROWS("Barrows", 3565, 3306),
	MORTTON("Mort'ton", 3485, 3275),
	PATERDOMUS("Paterdomus", 3405, 3485),

	// Kharidian Desert
	SHANTAY_PASS("Shantay Pass", 3304, 3116),
	POLLNIVNEACH("Pollnivneach", 3359, 2967),
	NARDAH("Nardah", 3428, 2892),
	SOPHANEM("Sophanem", 3300, 2785),
	BANDIT_CAMP("Bandit Camp", 3170, 2985),
	UZER("Uzer", 3480, 3080),

	// Karamja
	BRIMHAVEN("Brimhaven", 2760, 3178),
	SHILO_VILLAGE("Shilo Village", 2852, 2952),
	TAI_BWO_WANNAI("Tai Bwo Wannai", 2795, 3065),
	MUSA_POINT("Musa Point", 2915, 3155),
	KARAMJA_VOLCANO("Karamja Volcano", 2857, 3167),

	// Wilderness
	FEROX_ENCLAVE("Ferox Enclave", 3130, 3630),
	MAGE_ARENA("Mage Arena", 3105, 3935),
	CHAOS_TEMPLE("Chaos Temple", 3236, 3609),
	LAVA_MAZE("Lava Maze", 3070, 3840),
	WILDERNESS_DITCH("Wilderness Ditch", 3117, 3520),

	// Fremennik
	RELLEKKA("Rellekka", 2660, 3660),
	LIGHTHOUSE("Lighthouse", 2510, 3635),

	// Tirannwn
	PRIFDDINAS("Prifddinas", 2240, 3350),
	LLETYA("Lletya", 2340, 3170),
	PORT_TYRAS("Port Tyras", 2145, 3110),

	// Kourend and Varlamore
	HOSIDIUS("Hosidius", 1740, 3550),
	SHAYZIEN("Shayzien", 1500, 3620),
	LOVAKENGJ("Lovakengj", 1520, 3740),
	ARCEUUS("Arceuus", 1700, 3720),
	PORT_PISCARILIUS("Port Piscarilius", 1790, 3686),
	KOUREND_CASTLE("Kourend Castle", 1610, 3670),
	WOODCUTTING_GUILD("Woodcutting Guild", 1660, 3500),
	CIVITAS_ILLA_FORTIS("Civitas illa Fortis", 1690, 3130),

	// Feldip and the southern isles
	GU_TANOTH("Gu'Tanoth", 2540, 3020),
	MARIM("Marim", 2760, 2790);

	/**
	 * Flat, and far above the 1200 a Capital parcel costs.
	 *
	 * Deliberately not scaled by district: a landmark is worth owning because of
	 * what stands on it, not what the terrain classifier made of the ground. The
	 * Wilderness Ditch should cost what Varrock Square costs.
	 */
	public static final int PRICE = 5000;

	private final String displayName;
	private final int x;
	private final int y;
}
