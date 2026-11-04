/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.rrm.modules;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.facebook.openwifi.cloudsdk.UCentralApConfiguration;
import com.facebook.openwifi.rrm.DeviceConfig;
import com.facebook.openwifi.rrm.DeviceDataManager;
import com.facebook.openwifi.rrm.DeviceTopology;
import com.facebook.openwifi.rrm.RRMConfig;

class ConfigManagerWifiTest {
	DeviceDataManager data;
	TestWifiClient client;
	ConfigManager manager;
	RRMConfig config;

	@BeforeEach
	void setup() {
		data = new DeviceDataManager();
		DeviceTopology topology = new DeviceTopology();
		topology.put("zone", Collections.singleton(TestWifiClient.SERIAL));
		data.setTopology(topology);
		client = new TestWifiClient();
		config = new RRMConfig();
		config.moduleConfig.configManagerParams.configDebounceIntervalSec = -1;
		manager = new ConfigManager(
			config.moduleConfig.configManagerParams,
			data,
			client
		);
	}

	@Test
	void queuesAndDeliversNumericWidthWithoutChangingOtherSettings() {
		assertTrue(manager.set2gBandwidth(client.devices.get(0), 40));
		assertEquals(
			40,
			data.getDeviceConfig(TestWifiClient.SERIAL).userChannelWidths.get("2G")
		);
		manager.runImpl();
		assertEquals(1, client.configurations.size());
		UCentralApConfiguration delivered =
			new UCentralApConfiguration(client.configurations.get(0));
		assertTrue(
			delivered.getRadioConfig(0).getAsJsonPrimitive("channel-width").isNumber()
		);
		assertEquals(
			40,
			delivered.getRadioConfig(0).get("channel-width").getAsInt()
		);
		assertEquals(6, delivered.getRadioConfig(0).get("channel").getAsInt());
		assertEquals(
			17,
			delivered.getRadioConfig(0).get("tx-power").getAsInt()
		);
		assertEquals(
			client.devices.get(0).configuration.getAsJsonArray("radios").get(1),
			delivered.getRadioConfig(1)
		);
	}

	@Test
	void identicalAppliedRequestStoresOverrideWithoutScheduling() {
		assertFalse(manager.set2gBandwidth(client.devices.get(0), 20));
		manager.runImpl();
		assertTrue(client.configurations.isEmpty());
		assertFalse(manager.set2gBandwidth(client.devices.get(0), 20));
	}

	@Test
	void overridesInheritedWidthAndPreservesOtherBandsAndApFields() {
		DeviceConfig inherited = new DeviceConfig();
		inherited.userChannelWidths = new HashMap<>();
		inherited.userChannelWidths.put("2G", 40);
		inherited.userChannelWidths.put("5G", 80);
		data.setDeviceNetworkConfig(inherited);
		DeviceConfig ap = new DeviceConfig();
		ap.boundary = 12;
		data.setDeviceApConfig(TestWifiClient.SERIAL, ap);
		manager.set2gBandwidth(client.devices.get(0), 20);
		DeviceConfig selected = data.getDeviceConfig(TestWifiClient.SERIAL);
		assertEquals(20, selected.userChannelWidths.get("2G"));
		assertEquals(80, selected.userChannelWidths.get("5G"));
		assertEquals(12, selected.boundary);
		assertEquals(40, inherited.userChannelWidths.get("2G"));
	}

	@Test
	void rejectsDisabledConfigWithoutStoringSelection() {
		config.moduleConfig.configManagerParams.configEnabled = false;
		assertThrows(
			IllegalStateException.class,
			() -> manager.set2gBandwidth(client.devices.get(0), 40)
		);
		config.moduleConfig.configManagerParams.configEnabled = true;
		DeviceConfig ap = new DeviceConfig();
		ap.enableConfig = false;
		data.setDeviceApConfig(TestWifiClient.SERIAL, ap);
		assertThrows(
			IllegalStateException.class,
			() -> manager.set2gBandwidth(client.devices.get(0), 40)
		);
		assertNull(
			data.getDeviceConfig(TestWifiClient.SERIAL).userChannelWidths
		);
	}

	@Test
	void rejectsConstraintsCapabilitiesAndRegulatoryMismatch() {
		DeviceConfig ap = new DeviceConfig();
		ap.allowedChannelWidths =
			Collections.singletonMap("2G", Collections.singletonList(20));
		data.setDeviceApConfig(TestWifiClient.SERIAL, ap);
		assertThrows(
			IllegalArgumentException.class,
			() -> manager.set2gBandwidth(client.devices.get(0), 40)
		);
		data.setDeviceApConfig(TestWifiClient.SERIAL, null);
		client.capabilities.capabilities.wifi.get("phy0").htmode =
			new String[] { "HT20" };
		assertThrows(
			IllegalArgumentException.class,
			() -> manager.set2gBandwidth(client.devices.get(0), 40)
		);
		client.capabilities.capabilities.wifi.get("phy0").htmode =
			new String[] { "HT40" };
		client.capabilities.capabilities.wifi.get("phy0").country = "JP";
		assertThrows(
			IllegalArgumentException.class,
			() -> manager.set2gBandwidth(client.devices.get(0), 40)
		);
		client.capabilities.capabilities.wifi.get("phy0").country = "US";
		client.capabilities.capabilities.wifi.get("phy0").channels =
			new int[] { 6 };
		assertThrows(
			IllegalArgumentException.class,
			() -> manager.set2gBandwidth(client.devices.get(0), 40)
		);
		client.capabilities = null;
		assertThrows(
			IllegalArgumentException.class,
			() -> manager.set2gBandwidth(client.devices.get(0), 20)
		);
	}

	@Test
	void retriesRejectedDeliveryAndDebouncedUpdates() {
		manager.set2gBandwidth(client.devices.get(0), 40);
		client.configResult = null;
		manager.runImpl();
		manager.runImpl();
		assertEquals(2, client.configurations.size());
		client.configResult = TestWifiClient.accepted("configure");
		manager.runImpl();
		config.moduleConfig.configManagerParams.configDebounceIntervalSec =
			3600;
		manager.set2gBandwidth(client.devices.get(0), 40);
		manager.runImpl();
		assertEquals(3, client.configurations.size());
		config.moduleConfig.configManagerParams.configDebounceIntervalSec = -1;
		manager.runImpl();
		assertEquals(4, client.configurations.size());
	}

	@Test
	void retainsAcceptedChangeAcrossDisconnectBeforeDelivery() {
		manager.set2gBandwidth(client.devices.get(0), 40);
		client.devices.get(0).connected = false;
		manager.runImpl();
		assertTrue(client.configurations.isEmpty());
		client.devices.get(0).connected = true;
		manager.runImpl();
		assertEquals(1, client.configurations.size());
	}

	@Test
	void rejectsMissingRadioAndDoesNotApplyUnselectedWidths() {
		UCentralApConfiguration before =
			new UCentralApConfiguration(client.devices.get(0).configuration);
		assertFalse(manager.applyRRMConfig(TestWifiClient.SERIAL, before));
		client.devices.get(0).configuration.getAsJsonArray("radios").remove(0);
		assertThrows(
			IllegalArgumentException.class,
			() -> manager.set2gBandwidth(client.devices.get(0), 40)
		);
	}

	@Test
	void persistsAcrossRestartAndRollsBackFailedWrite(@TempDir Path dir)
		throws Exception {
		Path topology = dir.resolve("topology.json");
		Path settings = dir.resolve("config.json");
		DeviceDataManager persisted =
			new DeviceDataManager(topology.toFile(), settings.toFile());
		persisted.setTopology(data.getTopologyCopy());
		ConfigManager writer = new ConfigManager(
			config.moduleConfig.configManagerParams,
			persisted,
			client
		);
		writer.set2gBandwidth(client.devices.get(0), 40);
		assertEquals(
			40,
			new DeviceDataManager(topology.toFile(), settings.toFile())
				.getDeviceConfig(TestWifiClient.SERIAL).userChannelWidths
					.get("2G")
		);
		Files.delete(settings);
		Files.createDirectory(settings);
		assertThrows(
			RuntimeException.class,
			() -> writer.set2gBandwidth(client.devices.get(0), 20)
		);
		assertEquals(
			40,
			persisted.getDeviceConfig(TestWifiClient.SERIAL).userChannelWidths.get("2G")
		);
	}
}
