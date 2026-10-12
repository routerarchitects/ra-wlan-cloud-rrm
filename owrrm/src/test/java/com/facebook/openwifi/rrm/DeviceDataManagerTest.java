/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.rrm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;

import com.facebook.openwifi.cloudsdk.UCentralConstants;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

@TestMethodOrder(OrderAnnotation.class)
public class DeviceDataManagerTest {
	@Test
	@Order(1)
	void testTopology() throws Exception {
		final String zoneA = "test-zone-A";
		final String zoneB = "test-zone-B";
		final String zoneUnknown = "test-zone-unknown";
		final String deviceA1 = "aaaaaaaaaa01";
		final String deviceA2 = "aaaaaaaaaa02";
		final String deviceB1 = "bbbbbbbbbb01";
		final String deviceUnknown = "000000abcdef";

		DeviceDataManager deviceDataManager = new DeviceDataManager();

		// Empty topology
		assertFalse(deviceDataManager.isDeviceInTopology(deviceA1));
		assertNull(deviceDataManager.getDeviceZone(deviceA1));
		assertFalse(deviceDataManager.isZoneInTopology(zoneA));

		// Create topology with zones [A, B]
		DeviceTopology topology = new DeviceTopology();
		topology.put(zoneA, new TreeSet<>(Arrays.asList(deviceA1, deviceA2)));
		topology.put(zoneB, new TreeSet<>(Arrays.asList(deviceB1)));
		deviceDataManager.setTopology(topology);

		// Test device/zone getters
		assertTrue(deviceDataManager.isDeviceInTopology(deviceA1));
		assertEquals(zoneA, deviceDataManager.getDeviceZone(deviceA1));
		assertEquals(zoneA, deviceDataManager.getZoneForSerial(deviceA1));
		assertTrue(deviceDataManager.isDeviceInTopology(deviceA2));
		assertEquals(zoneA, deviceDataManager.getDeviceZone(deviceA2));
		assertEquals(zoneA, deviceDataManager.getZoneForSerial(deviceA2));
		assertTrue(deviceDataManager.isDeviceInTopology(deviceB1));
		assertEquals(zoneB, deviceDataManager.getDeviceZone(deviceB1));
		assertEquals(zoneB, deviceDataManager.getZoneForSerial(deviceB1));
		assertFalse(deviceDataManager.isDeviceInTopology(deviceUnknown));
		assertNull(deviceDataManager.getDeviceZone(deviceUnknown));
		assertNull(deviceDataManager.getZoneForSerial(deviceUnknown));
		assertTrue(deviceDataManager.isZoneInTopology(zoneA));
		assertTrue(deviceDataManager.isZoneInTopology(zoneB));
		assertFalse(deviceDataManager.isZoneInTopology(zoneUnknown));
		assertEquals(Arrays.asList(zoneA, zoneB), deviceDataManager.getZones());

		// Minimal JSON sanity check
		assertFalse(deviceDataManager.getTopologyJson().isEmpty());
	}

	@Test
	@Order(2)
	void testTopologyErrorHandling() throws Exception {
		DeviceDataManager deviceDataManager = new DeviceDataManager();

		// Null/empty argument handling
		assertFalse(deviceDataManager.isDeviceInTopology(null));
		assertFalse(deviceDataManager.isDeviceInTopology(""));
		assertNull(deviceDataManager.getDeviceZone(null));
		assertNull(deviceDataManager.getDeviceZone(""));
		assertNull(deviceDataManager.getZoneForSerial(null));
		assertNull(deviceDataManager.getZoneForSerial(""));
		assertFalse(deviceDataManager.isZoneInTopology(null));
		assertFalse(deviceDataManager.isZoneInTopology(""));
	}

	@Test
	@Order(3)
	void testTopologyExceptions() throws Exception {
		final String zone = "test-zone";
		final String deviceA = "aaaaaaaaaaaa";

		DeviceDataManager deviceDataManager = new DeviceDataManager();

		// Null topology
		IllegalArgumentException exception = assertThrows(
			IllegalArgumentException.class,
			() -> deviceDataManager.setTopology(null)
		);
		assertEquals("Topology cannot be null", exception.getMessage());

		// Null device list
		final DeviceTopology topologyNullDevices = new DeviceTopology();
		topologyNullDevices.put(zone, null);
		exception = assertThrows(
			IllegalArgumentException.class,
			() -> deviceDataManager.setTopology(topologyNullDevices)
		);
		assertEquals(
			"Device list cannot be null for zone 'test-zone'",
			exception.getMessage()
		);

		// Empty zone name
		final DeviceTopology topologyEmptyZone = new DeviceTopology();
		topologyEmptyZone.put("", new TreeSet<>(Arrays.asList(deviceA)));
		exception = assertThrows(
			IllegalArgumentException.class,
			() -> deviceDataManager.setTopology(topologyEmptyZone)
		);
		assertEquals(
			"Zone name cannot be null or empty",
			exception.getMessage()
		);

		// Empty serial number
		final DeviceTopology topologyEmptySerial = new DeviceTopology();
		topologyEmptySerial.put(zone, new TreeSet<>(Arrays.asList("")));
		exception = assertThrows(
			IllegalArgumentException.class,
			() -> deviceDataManager.setTopology(topologyEmptySerial)
		);
		assertEquals(
			"Device serial number cannot be null or empty",
			exception.getMessage()
		);

		// Null serial number
		final Set<String> devicesWithNullSerial = new HashSet<>();
		devicesWithNullSerial.add(null);
		final DeviceTopology topologyNullSerial = new DeviceTopology();
		topologyNullSerial.put(zone, devicesWithNullSerial);
		exception = assertThrows(
			IllegalArgumentException.class,
			() -> deviceDataManager.setTopology(topologyNullSerial)
		);
		assertEquals(
			"Device serial number cannot be null or empty",
			exception.getMessage()
		);

		// Same device in multiple zones
		final DeviceTopology topologyDupSerial = new DeviceTopology();
		final String zone2 = zone + "-copy";
		topologyDupSerial.put(zone, new TreeSet<>(Arrays.asList(deviceA)));
		topologyDupSerial.put(zone2, new TreeSet<>(Arrays.asList(deviceA)));
		exception = assertThrows(
			IllegalArgumentException.class,
			() -> deviceDataManager.setTopology(topologyDupSerial)
		);
		assertEquals(
			String.format(
				"Device '%s' in multiple zones ('%s', '%s')",
				deviceA,
				zone,
				zone2
			),
			exception.getMessage()
		);
	}

	@Test
	@Order(4)
	void testReverseZoneIndexUpdates() throws Exception {
		final String zoneA = "test-zone-A";
		final String zoneB = "test-zone-B";
		final String zoneC = "test-zone-C";
		final String device1 = "aaaaaaaaaa01";
		final String device2 = "aaaaaaaaaa02";
		final String device3 = "bbbbbbbbbb01";
		final String device4 = "cccccccccc01";

		DeviceDataManager deviceDataManager = new DeviceDataManager();

		DeviceTopology topology = new DeviceTopology();
		topology.put(zoneA, new TreeSet<>(Arrays.asList(device1, device2)));
		topology.put(zoneB, new TreeSet<>(Arrays.asList(device3)));
		deviceDataManager.setTopology(topology);
		assertEquals(zoneA, deviceDataManager.getZoneForSerial(device1));
		assertEquals(zoneA, deviceDataManager.getZoneForSerial(device2));
		assertEquals(zoneB, deviceDataManager.getZoneForSerial(device3));

		DeviceTopology movedTopology = new DeviceTopology();
		movedTopology.put(zoneA, new TreeSet<>(Arrays.asList(device1)));
		movedTopology.put(zoneB, new TreeSet<>(Arrays.asList(device2, device3)));
		deviceDataManager.setTopology(movedTopology);
		assertEquals(zoneB, deviceDataManager.getZoneForSerial(device2));

		DeviceTopology replacedTopology = new DeviceTopology();
		replacedTopology.put(zoneC, new TreeSet<>(Arrays.asList(device4)));
		deviceDataManager.setTopology(replacedTopology);
		assertNull(deviceDataManager.getZoneForSerial(device1));
		assertNull(deviceDataManager.getZoneForSerial(device2));
		assertNull(deviceDataManager.getZoneForSerial(device3));
		assertEquals(zoneC, deviceDataManager.getZoneForSerial(device4));

		deviceDataManager.setTopology(new DeviceTopology());
		assertNull(deviceDataManager.getZoneForSerial(device4));
	}

	@Test
	@Order(5)
	void testSetTopologyCopiesOriginalTopology() throws Exception {
		final String zoneA = "Zone-A";
		final String zoneB = "Zone-B";
		final String device1 = "AP1";
		final String device2 = "AP2";

		DeviceDataManager deviceDataManager = new DeviceDataManager();
		DeviceTopology topology = new DeviceTopology();
		topology.put(zoneA, new TreeSet<>(Arrays.asList(device1)));

		deviceDataManager.setTopology(topology);
		topology.put(zoneB, new TreeSet<>(Arrays.asList(device2)));

		assertEquals(zoneA, deviceDataManager.getZoneForSerial(device1));
		assertNull(deviceDataManager.getZoneForSerial(device2));
		assertFalse(deviceDataManager.isZoneInTopology(zoneB));
		assertFalse(deviceDataManager.getTopologyJson().contains(device2));
		assertFalse(deviceDataManager.getTopologyJson().contains(zoneB));
	}

	@Test
	@Order(6)
	void testSetTopologyCopiesNestedDeviceSets() throws Exception {
		final String zoneA = "Zone-A";
		final String device1 = "AP1";
		final String device2 = "AP2";

		DeviceDataManager deviceDataManager = new DeviceDataManager();
		Set<String> devices = new TreeSet<>();
		devices.add(device1);
		DeviceTopology topology = new DeviceTopology();
		topology.put(zoneA, devices);

		deviceDataManager.setTopology(topology);
		devices.add(device2);
		devices.remove(device1);

		assertEquals(zoneA, deviceDataManager.getZoneForSerial(device1));
		assertNull(deviceDataManager.getZoneForSerial(device2));
		assertTrue(deviceDataManager.isDeviceInTopology(device1));
		assertFalse(deviceDataManager.isDeviceInTopology(device2));
	}

	@Test
	@Order(7)
	void testRejectedTopologyUpdatePreservesExistingTopology()
		throws Exception {
		final String zoneA = "test-zone-A";
		final String zoneB = "test-zone-B";
		final String deviceA = "aaaaaaaaaa01";
		final String deviceB = "bbbbbbbbbb01";

		DeviceDataManager deviceDataManager = new DeviceDataManager();
		DeviceTopology topology = new DeviceTopology();
		topology.put(zoneA, new TreeSet<>(Arrays.asList(deviceA)));
		deviceDataManager.setTopology(topology);
		String originalTopologyJson = deviceDataManager.getTopologyJson();

		DeviceTopology invalidTopology = new DeviceTopology();
		invalidTopology.put(zoneB, null);
		IllegalArgumentException exception = assertThrows(
			IllegalArgumentException.class,
			() -> deviceDataManager.setTopology(invalidTopology)
		);
		assertEquals(
			"Device list cannot be null for zone 'test-zone-B'",
			exception.getMessage()
		);

		assertEquals(originalTopologyJson, deviceDataManager.getTopologyJson());
		assertEquals(zoneA, deviceDataManager.getZoneForSerial(deviceA));
		assertNull(deviceDataManager.getZoneForSerial(deviceB));
		assertFalse(deviceDataManager.isZoneInTopology(zoneB));
	}

	@Test
	@Order(8)
	void testReverseZoneIndexFromDisk(@TempDir Path tempDir) throws Exception {
		final String zone = "test-zone";
		final String device = "aaaaaaaaaa01";
		File topologyFile = tempDir.resolve("topology.json").toFile();
		File deviceConfigFile = tempDir.resolve("device-config.json").toFile();

		DeviceTopology topology = new DeviceTopology();
		topology.put(zone, new TreeSet<>(Arrays.asList(device)));
		Utils.writeJsonFile(topologyFile, topology);

		DeviceDataManager deviceDataManager =
			new DeviceDataManager(topologyFile, deviceConfigFile);
		assertEquals(zone, deviceDataManager.getZoneForSerial(device));
	}

	@Test
	@Order(9)
	void testConcurrentTopologyUpdatesAndLookups() throws Exception {
		final String zoneA = "test-zone-A";
		final String zoneB = "test-zone-B";
		final String device = "aaaaaaaaaa01";
		final int iterations = 500;

		DeviceTopology topologyA = new DeviceTopology();
		topologyA.put(zoneA, new TreeSet<>(Arrays.asList(device)));
		DeviceTopology topologyB = new DeviceTopology();
		topologyB.put(zoneB, new TreeSet<>(Arrays.asList(device)));

		DeviceDataManager deviceDataManager = new DeviceDataManager();
		deviceDataManager.setTopology(topologyA);

		CountDownLatch start = new CountDownLatch(1);
		AtomicBoolean failed = new AtomicBoolean(false);
		ExecutorService executor = Executors.newFixedThreadPool(4);
		executor.submit(() -> {
			try {
				start.await();
				for (int i = 0; i < iterations; i++) {
					deviceDataManager.setTopology((i % 2 == 0)
						? topologyB
						: topologyA);
				}
			} catch (Exception e) {
				failed.set(true);
			}
		});
		for (int i = 0; i < 3; i++) {
			executor.submit(() -> {
				try {
					start.await();
					for (int j = 0; j < iterations; j++) {
						String zone = deviceDataManager.getZoneForSerial(device);
						if (!zoneA.equals(zone) && !zoneB.equals(zone)) {
							failed.set(true);
							break;
						}
					}
				} catch (Exception e) {
					failed.set(true);
				}
			});
		}
		start.countDown();
		executor.shutdown();
		assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
		assertFalse(failed.get());
	}

	@Test
	@Order(101)
	void testDeviceConfig() throws Exception {
		final String zoneA = "test-zone-A";
		final String zoneB = "test-zone-B";
		final String deviceA = "aaaaaaaaaa01";
		final String deviceB = "bbbbbbbbbb01";
		final String deviceUnknown = "000000abcdef";

		DeviceDataManager deviceDataManager = new DeviceDataManager();

		// Create topology with zones [A, B]
		DeviceTopology topology = new DeviceTopology();
		topology.put(zoneA, new TreeSet<>(Arrays.asList(deviceA)));
		topology.put(zoneB, new TreeSet<>(Arrays.asList(deviceB)));
		deviceDataManager.setTopology(topology);

		// Update network config
		final DeviceConfig networkCfg = new DeviceConfig();
		networkCfg.enableRRM = false;
		deviceDataManager.setDeviceNetworkConfig(networkCfg);

		// Update zone config
		final DeviceConfig zoneCfgA = new DeviceConfig();
		zoneCfgA.enableRRM = true;
		deviceDataManager.setDeviceZoneConfig(zoneA, zoneCfgA);

		// Update device config
		final DeviceConfig apCfgA = new DeviceConfig();
		final DeviceConfig apCfgB = new DeviceConfig();
		apCfgA.allowedChannels = new HashMap<>();
		apCfgA.allowedChannels
			.put(UCentralConstants.BAND_2G, Arrays.asList(6, 7));
		apCfgB.allowedChannels = new HashMap<>();
		apCfgB.allowedChannels
			.put(UCentralConstants.BAND_2G, Arrays.asList(1, 2, 3));
		// - use setter
		deviceDataManager.setDeviceApConfig(deviceA, apCfgA);
		// - use update function
		deviceDataManager.updateDeviceApConfig(apConfig -> {
			apConfig.put(deviceB, apCfgB);
			apConfig.put(deviceUnknown, new DeviceConfig());
		});

		// Check current layered device config
		DeviceConfig actualApCfgA = deviceDataManager.getDeviceConfig(deviceA);
		DeviceConfig actualApCfgB = deviceDataManager.getDeviceConfig(deviceB);
		assertNull(deviceDataManager.getDeviceConfig(deviceUnknown));
		assertNotNull(actualApCfgA);
		assertNotNull(actualApCfgB);
		assertTrue(actualApCfgA.enableRRM);
		assertFalse(actualApCfgB.enableRRM);
		assertEquals(
			2,
			actualApCfgA.allowedChannels.get(UCentralConstants.BAND_2G).size()
		);
		assertEquals(
			3,
			actualApCfgB.allowedChannels.get(UCentralConstants.BAND_2G).size()
		);
		DeviceConfig actualZoneCfgA = deviceDataManager.getZoneConfig(zoneA);
		assertNotNull(actualZoneCfgA);
		assertTrue(actualZoneCfgA.enableRRM);

		// Minimal JSON sanity check
		assertFalse(deviceDataManager.getDeviceLayeredConfigJson().isEmpty());

		// Setting null config at a single layer is allowed
		deviceDataManager.setDeviceNetworkConfig(null);
		deviceDataManager.setDeviceZoneConfig(zoneA, null);
		deviceDataManager.setDeviceApConfig(deviceA, null);

		// Setting whole layered config works (even with null fields)
		DeviceLayeredConfig nullLayeredCfg = new DeviceLayeredConfig();
		nullLayeredCfg.networkConfig = null;
		nullLayeredCfg.zoneConfig = null;
		nullLayeredCfg.apConfig = null;
		deviceDataManager.setDeviceLayeredConfig(nullLayeredCfg);
		deviceDataManager.setDeviceLayeredConfig(new DeviceLayeredConfig());
	}

	@Test
	@Order(102)
	void testDeviceConfigErrorHandling() throws Exception {
		final String zoneUnknown = "test-zone-unknown";
		final String deviceUnknown = "000000abcdef";

		DeviceDataManager deviceDataManager = new DeviceDataManager();

		// Unknown devices/zones (getters)
		assertNull(deviceDataManager.getDeviceConfig(deviceUnknown));
		assertNull(deviceDataManager.getAllDeviceConfigs(null));
		assertNull(deviceDataManager.getAllDeviceConfigs(zoneUnknown));
	}

	@Test
	@Order(103)
	void testDeviceConfigExceptions() throws Exception {
		final String zoneUnknown = "test-zone-unknown";
		final String deviceUnknown = "000000abcdef";

		DeviceDataManager deviceDataManager = new DeviceDataManager();

		// Null config
		assertThrows(
			NullPointerException.class,
			() -> {
				deviceDataManager.setDeviceLayeredConfig(null);
			}
		);

		// Null/empty arguments
		assertThrows(
			IllegalArgumentException.class,
			() -> {
				deviceDataManager.getDeviceConfig(null);
			}
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> {
				deviceDataManager.getDeviceConfig(null, zoneUnknown);
			}
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> {
				deviceDataManager.getDeviceConfig(deviceUnknown, null);
			}
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> {
				deviceDataManager.setDeviceZoneConfig(null, null);
			}
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> {
				deviceDataManager.setDeviceZoneConfig("", null);
			}
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> {
				deviceDataManager.setDeviceApConfig(null, null);
			}
		);
		Assertions.assertThrows(
			IllegalArgumentException.class,
			() -> {
				deviceDataManager.setDeviceApConfig("", null);
			}
		);

		// Unknown devices/zones (setters)
		final DeviceConfig cfg = new DeviceConfig();
		assertThrows(
			IllegalArgumentException.class,
			() -> {
				deviceDataManager.setDeviceZoneConfig(zoneUnknown, cfg);
			}
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> {
				deviceDataManager.setDeviceApConfig(deviceUnknown, cfg);
			}
		);
	}
}
