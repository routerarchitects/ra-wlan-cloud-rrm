/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.cloudsdk;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class WifiSecurityTest {
	JsonObject payload;
	JsonObject entry;
	JsonObject rsn;

	@BeforeEach
	void setup() throws Exception {
		try (
			InputStreamReader reader = new InputStreamReader(
				getClass().getResourceAsStream("/wifi-scan-rsn.json"),
				StandardCharsets.UTF_8
			)
		) {
			payload = new Gson().fromJson(reader, JsonObject.class);
		}
		entry = payload.getAsJsonObject("status")
			.getAsJsonArray("scan")
			.get(0)
			.getAsJsonObject();
		rsn = entry.getAsJsonArray("ies")
			.get(0)
			.getAsJsonObject()
			.getAsJsonObject("content");
	}

	private void akms(String... names) {
		JsonArray suites = new JsonArray();
		for (String name : names) {
			JsonObject suite = new JsonObject();
			suite.addProperty("Auth Key Management (AKM) OUI", "00:0f:ac");
			suite.addProperty("Auth Key Management (AKM) type", name);
			suites.add(suite);
		}
		rsn.add("Auth Key Management (AKM) List", suites);
	}

	@Test
	void classifiesRealisticWpa2AndDoesNotTrustIncomingEncryption() {
		assertEquals("WPA2", WifiSecurity.detect(entry));
		for (
			String akm : new String[] { "PSK",
				"PSK (SHA256)",
				"WPA",
				"WPA (SHA256)",
				"FT using PSK",
				"802.1X" }
		) {
			akms(akm);
			assertEquals("WPA2", WifiSecurity.detect(entry), akm);
		}
		entry.addProperty("encryption", "OPEN");
		List<WifiScanEntry> entries =
			UCentralUtils.parseWifiScanEntries(payload, 123456);
		assertNotNull(entries);
		assertEquals(123456, entries.get(0).unixTimeMs);
		assertEquals("WPA2", entries.get(0).encryption);
		assertEquals("WPA2", new WifiScanEntry(entries.get(0)).encryption);
		assertNotNull(entries.get(0).ieContainer);
	}

	@Test
	void unrelatedVendorIesDoNotInvalidateValidRsn() {
		for (String vendorIe : new String[] {
			"{\"type\":221,\"data\":\"...\"}",
			"{\"type\":221,\"content\":null}",
			"{\"type\":221,\"content\":\"undecoded\"}",
			"{\"type\":221,\"content\":[]}",
			"{\"type\":221,\"content\":{}}",
			"{\"type\":221,\"content\":{\"vendor\":[],\"type\":{}}}",
			"{\"type\":221,\"content\":{\"vendor\":\"Wi-Fi : WPA / WME\",\"type\":\"WMM/WME\"}}"
		}) {
			JsonArray ies = entry.getAsJsonArray("ies");
			ies.add(new Gson().fromJson(vendorIe, JsonObject.class));
			assertEquals("WPA2", WifiSecurity.detect(entry), vendorIe);
			assertEquals("WPA2",
				UCentralUtils.parseWifiScanEntries(payload, 1000).get(0).encryption);
			// Classification must also be independent of IE ordering.
			JsonArray reversed = new JsonArray();
			reversed.add(ies.get(1));
			reversed.add(ies.get(0));
			entry.add("ies", reversed);
			assertEquals("WPA2", WifiSecurity.detect(entry), vendorIe);
			entry.add("ies", ies);
			ies.remove(1);
		}
	}

	@Test
	void positivelyIdentifiedMalformedWpaStillInvalidatesValidRsn() {
		entry.getAsJsonArray("ies").add(new Gson().fromJson(
			"{\"type\":221,\"content\":{\"vendor\":\"Wi-Fi : WPA / WME\",\"type\":\"WPA Information Element\"}}",
			JsonObject.class));
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
	}

	@Test
	void requiresPmfAndStrongCiphersForWpa3AndTransition() {
		akms("SAE");
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
		rsn.getAsJsonObject("RSN Capabilities")
			.addProperty("Management Frame Protection Required", 1);
		assertEquals("WPA3", WifiSecurity.detect(entry));
		akms("8");
		assertEquals("WPA3", WifiSecurity.detect(entry));
		akms("PSK", "SAE");
		rsn.getAsJsonObject("RSN Capabilities")
			.addProperty("Management Frame Protection Required", 0);
		assertEquals("WPA2/WPA3", WifiSecurity.detect(entry));
		rsn.addProperty("Group Cipher Suite type", "TKIP");
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
	}

	@Test
	void unknownAkmsAndMalformedSecurityRemainUnknownWithoutLosingBatch() {
		akms("unknown");
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
		akms("PSK", "unknown");
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
		akms("OWE");
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
		akms("PSK");
		rsn.remove("Pairwise Cipher Suite List");
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
		assertEquals(
			"UNKNOWN",
			UCentralUtils.parseWifiScanEntries(payload, 1000).get(0).encryption
		);
		entry.getAsJsonArray("ies").add("not an IE object");
		assertEquals(
			"UNKNOWN",
			UCentralUtils.parseWifiScanEntries(payload, 1000).get(0).encryption
		);
		entry.addProperty("ies", "malformed");
		assertEquals(
			"UNKNOWN",
			UCentralUtils.parseWifiScanEntries(payload, 1000).get(0).encryption
		);
		entry.remove("ies");
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
	}

	@Test
	void suiteB192RequiresItsStrongCipherAndPmf() {
		akms("12");
		rsn.getAsJsonObject("RSN Capabilities")
			.addProperty("Management Frame Protection Required", 1);
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
		rsn.addProperty("Group Cipher Suite type", "GCMP-256");
		rsn.getAsJsonArray("Pairwise Cipher Suite List")
			.get(0)
			.getAsJsonObject()
			.addProperty("Group Cipher Suite type", "GCMP-256");
		assertEquals("WPA3", WifiSecurity.detect(entry));
		akms("802.1X", "SAE");
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
	}

	@Test
	void openRequiresPrivacyEvidenceAndProtectedIsNotAssumedWep() {
		entry.add("ies", new JsonArray());
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
		entry.addProperty("capability", 1);
		assertEquals("OPEN", WifiSecurity.detect(entry));
		entry.remove("capability");
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
		entry.addProperty("capability", 17);
		JsonObject challenge = new JsonObject();
		challenge.addProperty("type", 16);
		challenge.addProperty(
			"data",
			java.util.Base64.getEncoder().encodeToString(new byte[128])
		);
		entry.getAsJsonArray("ies").add(challenge);
		assertEquals("WEP", WifiSecurity.detect(entry));
	}

	@Test
	void wpaRequiresTheSpecificVendorSecurityIeAndValidSuites() {
		JsonObject wpa = new JsonObject();
		wpa.addProperty("vendor", "Wi-Fi : WPA / WME");
		wpa.addProperty("type", "WPA Information Element");
		wpa.addProperty("WPA Version", 1);
		wpa.addProperty("Multicast Cipher Suite type", "TKIP");
		JsonObject cipher = new JsonObject();
		cipher.addProperty("Unicast Cipher Suite OUI", "00 50 f2");
		cipher.addProperty("Unicast Cipher Suite type", "TKIP");
		JsonArray ciphers = new JsonArray();
		ciphers.add(cipher);
		wpa.add("Unicast Cipher Suite List", ciphers);
		JsonObject akm = new JsonObject();
		akm.addProperty("Auth Key Management (AKM) OUI", "00 50 f2");
		akm.addProperty("Auth Key Management (AKM) type", "PSK");
		JsonArray akms = new JsonArray();
		akms.add(akm);
		wpa.add("Auth Key Management (AKM) List", akms);
		JsonObject ie = new JsonObject();
		ie.addProperty("type", 221);
		ie.add("content", wpa);
		JsonArray ies = new JsonArray();
		ies.add(ie);
		entry.add("ies", ies);
		assertEquals("WPA", WifiSecurity.detect(entry));
		wpa.addProperty("type", "WMM/WME");
		assertEquals("UNKNOWN", WifiSecurity.detect(entry));
		entry.addProperty("capability", 1);
		assertEquals("OPEN", WifiSecurity.detect(entry));
	}

	@Test
	void malformedEntryInvalidatesWholeBatch() {
		payload.getAsJsonObject("status")
			.getAsJsonArray("scan")
			.add("invalid entry");
		assertNull(UCentralUtils.parseWifiScanEntries(payload, 1000));
	}
}
