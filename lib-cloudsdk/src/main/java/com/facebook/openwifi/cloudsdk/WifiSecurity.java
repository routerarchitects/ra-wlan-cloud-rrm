/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.cloudsdk;

import java.util.Arrays;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Conservative classification of the decoded OWGW scan information elements. */
public final class WifiSecurity {
	private WifiSecurity() {}

	/** Return UNKNOWN whenever the advertised security cannot be fully decoded. */
	public static String detect(JsonObject entry) {
		try {
			if (!entry.has("ies") || !entry.get("ies").isJsonArray()) {
				return "UNKNOWN";
			}
			String rsn = null;
			boolean wpa = false;
			boolean sharedKeyChallenge = false;
			for (JsonElement element : entry.getAsJsonArray("ies")) {
				if (!element.isJsonObject()) {
					return "UNKNOWN";
				}
				JsonObject ie = element.getAsJsonObject();
				int type = ie.get("type").getAsInt();
				if (type == 16 && ie.has("data")) {
					// Challenge Text is part of legacy WEP Shared Key authentication.
					sharedKeyChallenge = java.util.Base64.getDecoder()
						.decode(string(ie, "data")).length == 128;
				} else if (type == 48) {
					String mode = decode(ie.getAsJsonObject("content"), false);
					if (
						"UNKNOWN".equals(mode) ||
							(rsn != null && !rsn.equals(mode))
					) {
						return "UNKNOWN";
					}
					rsn = mode;
				} else if (type == 221) {
					JsonElement decoded = ie.get("content");
					if (decoded == null || !decoded.isJsonObject()) {
						continue; // An undecoded vendor IE does not identify WPA.
					}
					JsonObject content = decoded.getAsJsonObject();
					// WMM, WPS and other vendor IEs do not imply WPA.
					if (
						"Wi-Fi : WPA / WME".equals(string(content, "vendor")) &&
							"WPA Information Element"
								.equals(string(content, "type"))
					) {
						if (!"WPA".equals(decode(content, true))) {
							return "UNKNOWN";
						}
						wpa = true;
					}
				}
			}
			if (rsn != null || wpa) {
				if (
					entry.has("capability") &&
						(entry.get("capability").getAsInt() & 0x10) == 0
				) {
					return "UNKNOWN"; // Contradictory privacy information.
				}
				return rsn != null ? rsn : "WPA";
			}
			if (!entry.has("capability")) {
				return "UNKNOWN";
			}
			if ((entry.get("capability").getAsInt() & 0x10) == 0) {
				return "OPEN";
			}
			// Privacy alone cannot distinguish WEP from an undecoded protected network.
			// This decoder has no authoritative WEP-only evidence in that case.
			return sharedKeyChallenge ? "WEP" : "UNKNOWN";
		} catch (RuntimeException e) {
			return "UNKNOWN";
		}
	}

	private static String decode(JsonObject content, boolean legacy) {
		if (
			content == null || content
				.get(legacy ? "WPA Version" : "RSN Version")
				.getAsInt() != 1
		) {
			return "UNKNOWN";
		}
		String oui = legacy ? "00:50:f2" : "00:0f:ac";
		String group = legacy ? "Multicast Cipher Suite" : "Group Cipher Suite";
		if (!cipher(content, group, oui, legacy)) {
			return "UNKNOWN";
		}
		JsonArray pairwise = content.getAsJsonArray(
			legacy ? "Unicast Cipher Suite List" : "Pairwise Cipher Suite List"
		);
		if (pairwise == null || pairwise.size() == 0) {
			return "UNKNOWN";
		}
		if (
			content.has(
				"Pairwise Cipher Suite Count"
			) && content.get("Pairwise Cipher Suite Count").getAsInt() !=
				pairwise.size()
		) {
			return "UNKNOWN";
		}
		boolean strong = strongCipher(string(content, group + " type"));
		boolean suiteB192 = "GCMP-256".equals(string(content, group + " type"));
		for (JsonElement item : pairwise) {
			JsonObject suite = item.getAsJsonObject();
			// OWGW uses "Group Cipher Suite" keys inside the RSN pairwise list.
			String key = legacy ? "Unicast Cipher Suite" : "Group Cipher Suite";
			if (!cipher(suite, key, oui, legacy)) {
				return "UNKNOWN";
			}
			strong &= strongCipher(string(suite, key + " type"));
			suiteB192 &= "GCMP-256".equals(string(suite, key + " type"));
		}
		JsonArray akms =
			content.getAsJsonArray("Auth Key Management (AKM) List");
		if (akms == null || akms.size() == 0) {
			return "UNKNOWN";
		}
		if (
			content.has("Auth Key Management (AKM) Suite Count") &&
				content.get("Auth Key Management (AKM) Suite Count").getAsInt() != akms.size()
		) {
			return "UNKNOWN";
		}
		boolean wpa2 = false;
		boolean wpa3 = false;
		boolean enterprise = false;
		boolean enterprise192 = false;
		for (JsonElement item : akms) {
			JsonObject suite = item.getAsJsonObject();
			if (
				!oui.equals(
					normalizeOui(string(suite, "Auth Key Management (AKM) OUI"))
				)
			) {
				return "UNKNOWN";
			}
			String akm = string(suite, "Auth Key Management (AKM) type");
			// Some decoders preserve the original numeric suite selector.
			switch (akm) {
			case "1":
				akm = "802.1X";
				break;
			case "2":
				akm = "PSK";
				break;
			case "3":
				akm = "FT over IEEE 802.1X";
				break;
			case "4":
				akm = "FT using PSK";
				break;
			case "5":
				akm = "802.1X (SHA256)";
				break;
			case "6":
				akm = "PSK (SHA256)";
				break;
			case "8":
				akm = "SAE";
				break;
			case "9":
				akm = "FT/SAE";
				break;
			case "12":
				akm = "802.1X Suite B 192";
				break;
			}
			if (
				Arrays.asList(
					"PSK",
					"PSK (SHA256)",
					"WPA",
					"WPA (SHA256)",
					"802.1X",
					"802.1X (SHA256)",
					"FT over IEEE 802.1X",
					"FT using PSK"
				).contains(akm)
			) {
				wpa2 = true;
				enterprise |= Arrays.asList(
					"WPA",
					"WPA (SHA256)",
					"802.1X",
					"802.1X (SHA256)",
					"FT over IEEE 802.1X"
				).contains(akm);
			} else if (
				!legacy &&
					Arrays.asList("SAE", "FT using SAE", "FT/SAE").contains(akm)
			) {
				wpa3 = true;
			} else if (!legacy && "802.1X Suite B 192".equals(akm)) {
				wpa3 = true;
				enterprise192 = true;
			} else {
				return "UNKNOWN";
			}
		}
		if (legacy) {
			return wpa2 ? "WPA" : "UNKNOWN";
		}
		if (wpa3) {
			if (enterprise || (enterprise192 && (wpa2 || !suiteB192))) {
				return "UNKNOWN";
			}
			JsonObject caps = content.getAsJsonObject("RSN Capabilities");
			if (
				!strong || caps == null ||
					caps.get("Management Frame Protection Capable").getAsInt() != 1 ||
					(!wpa2 && caps.get("Management Frame Protection Required").getAsInt() != 1)
			) {
				return "UNKNOWN";
			}
			return wpa2 ? "WPA2/WPA3" : "WPA3";
		}
		return wpa2 ? "WPA2" : "UNKNOWN";
	}

	private static boolean cipher(
		JsonObject suite,
		String key,
		String oui,
		boolean legacy
	) {
		String value = string(suite, key + " type");
		// Legacy decoder omits the multicast OUI. Its explicit WPA vendor/type
		// and recognized cipher still identify that field.
		return (legacy && !suite.has(key + " OUI") ||
			oui.equals(normalizeOui(string(suite, key + " OUI")))) &&
			(strongCipher(value) || "TKIP".equals(value));
	}

	private static boolean strongCipher(String value) {
		return Arrays.asList(
			"AES (CCM)",
			"CCMP",
			"CCMP-128",
			"CCMP-256",
			"GCMP",
			"GCMP-128",
			"GCMP-256"
		).contains(value);
	}

	private static String normalizeOui(String value) {
		return value.toLowerCase(Locale.ROOT).replace(' ', ':');
	}

	private static String string(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive()
			? value.getAsString() : "";
	}
}
