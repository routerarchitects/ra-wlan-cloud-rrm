/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.rrm.modules;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.facebook.openwifi.cloudsdk.kafka.UCentralKafkaConsumer.KafkaRecord;
import com.facebook.openwifi.rrm.DeviceDataManager;
import com.facebook.openwifi.rrm.DeviceTopology;
import com.facebook.openwifi.rrm.RRMConfig;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

class ModelerWifiTest {
	Modeler modeler;
	DeviceDataManager data;

	@BeforeEach
	void setup() {
		RRMConfig config = new RRMConfig();
		config.moduleConfig.modelerParams.wifiScanBufferSize = 2;
		data = new DeviceDataManager();
		DeviceTopology topology = new DeviceTopology();
		topology.put("zone", Collections.singleton(TestWifiClient.SERIAL));
		data.setTopology(topology);
		TestWifiClient client = new TestWifiClient();
		ConfigManager manager = new ConfigManager(
			config.moduleConfig.configManagerParams,
			data,
			client
		);
		DataCollector collector = new DataCollector(
			config.moduleConfig.dataCollectorParams,
			data,
			client,
			null,
			manager,
			null
		);
		modeler = new Modeler(
			config.moduleConfig.modelerParams,
			data,
			null,
			client,
			collector,
			manager
		);
	}

	private void ingest(String serial, long timestamp, String scans) {
		JsonObject payload = new Gson().fromJson(
			"{\"status\":{\"scan\":" + scans + "}}",
			JsonObject.class
		);
		modeler.processData(
			modeler.new InputData(
				Modeler.InputDataType.WIFISCAN,
				Collections
					.singletonList(new KafkaRecord(serial, payload, timestamp))
			)
		);
	}

	@Test
	void latestBatchIsCompleteBoundedAndTimestampedEvenWhenEmpty() {
		ingest(
			TestWifiClient.SERIAL,
			1000,
			"[{\"ssid\":\"one\"},{\"ssid\":\"two\"}]"
		);
		assertEquals(
			2,
			modeler.getLatestWifiScan(TestWifiClient.SERIAL).entries.size()
		);
		ingest(TestWifiClient.SERIAL, 2000, "[{\"ssid\":\"new\"}]");
		assertEquals(
			"new",
			modeler.getLatestWifiScan(TestWifiClient.SERIAL).entries.get(0).ssid
		);
		ingest(TestWifiClient.SERIAL, 2500, "[]");
		assertEquals(
			2500,
			modeler.getLatestWifiScan(TestWifiClient.SERIAL).timestampMs
		);
		assertTrue(
			modeler.getLatestWifiScan(TestWifiClient.SERIAL).entries.isEmpty()
		);
		assertEquals(
			2,
			modeler.dataModel.latestWifiScans.get(TestWifiClient.SERIAL).size()
		);
		ingest(TestWifiClient.SERIAL, 3000, "[null]");
		assertEquals(
			2500,
			modeler.getLatestWifiScan(TestWifiClient.SERIAL).timestampMs
		);
		assertNull(modeler.getLatestWifiScan("other-device"));
		data.setTopology(new DeviceTopology());
		modeler.revalidate();
		assertNull(modeler.getLatestWifiScan(TestWifiClient.SERIAL));
	}

	@Test
	void delayedEventsAndTimestampTiesCannotReplaceLatestBatch() {
		ingest(TestWifiClient.SERIAL, 2000, "[{\"ssid\":\"new\"}]");
		ingest(TestWifiClient.SERIAL, 1500, "[{\"ssid\":\"old\"}]");
		assertEquals(2000, modeler.getLatestWifiScan(TestWifiClient.SERIAL).timestampMs);
		assertEquals("new", modeler.getLatestWifiScan(TestWifiClient.SERIAL).entries.get(0).ssid);
		ingest(TestWifiClient.SERIAL, 1500, "[]");
		ingest(TestWifiClient.SERIAL, 2000, "[]");
		assertEquals(2000, modeler.getLatestWifiScan(TestWifiClient.SERIAL).timestampMs);
		assertEquals("new", modeler.getLatestWifiScan(TestWifiClient.SERIAL).entries.get(0).ssid);
		ingest("other-device", 1500, "[]");
		assertEquals(1500, modeler.getLatestWifiScan("other-device").timestampMs);
		ingest(TestWifiClient.SERIAL, 2500, "[]");
		ingest(TestWifiClient.SERIAL, 2250, "[{\"ssid\":\"delayed\"}]");
		assertEquals(2500, modeler.getLatestWifiScan(TestWifiClient.SERIAL).timestampMs);
		assertTrue(modeler.getLatestWifiScan(TestWifiClient.SERIAL).entries.isEmpty());
	}

	@Test
	void concurrentIngestionAndReadsPublishOnlyCompleteSnapshots() {
		CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
			for (int i = 1; i <= 1000; i++) {
				ingest(
					TestWifiClient.SERIAL,
					i,
					"[{\"ssid\":\"a\"},{\"ssid\":\"b\"}]"
				);
			}
		});
		CompletableFuture<Void> delayedWriter = CompletableFuture.runAsync(() -> {
			for (int i = 1000; i >= 1; i--) {
				ingest(TestWifiClient.SERIAL, i,
					"[{\"ssid\":\"a\"},{\"ssid\":\"b\"}]");
			}
		});
		long lastTimestamp = 0;
		for (int i = 0; i < 2000; i++) {
			Modeler.WifiScanBatch batch =
				modeler.getLatestWifiScan(TestWifiClient.SERIAL);
			if (batch != null) {
				assertTrue(batch.timestampMs >= lastTimestamp);
				lastTimestamp = batch.timestampMs;
				assertEquals(2, batch.entries.size());
				assertEquals(
					batch.timestampMs,
					batch.entries.get(0).unixTimeMs
				);
				batch.entries.get(0).ssid = "local mutation";
				assertEquals(
					"a",
					modeler.getLatestWifiScan(TestWifiClient.SERIAL).entries.get(0).ssid
				);
			}
		}
		writer.join();
		delayedWriter.join();
		assertEquals(
			1000,
			modeler.getLatestWifiScan(TestWifiClient.SERIAL).timestampMs
		);
	}
}
