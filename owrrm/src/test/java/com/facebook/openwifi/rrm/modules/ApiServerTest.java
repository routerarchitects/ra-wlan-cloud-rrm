/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.rrm.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;

import com.facebook.openwifi.cloudsdk.models.gw.ServiceEvent;
import com.facebook.openwifi.cloudsdk.UCentralClient;
import com.facebook.openwifi.cloudsdk.UCentralConstants;
import com.facebook.openwifi.cloudsdk.kafka.UCentralKafkaConsumer;
import com.facebook.openwifi.rrm.DeviceConfig;
import com.facebook.openwifi.rrm.DeviceDataManager;
import com.facebook.openwifi.rrm.DeviceLayeredConfig;
import com.facebook.openwifi.rrm.DeviceTopology;
import com.facebook.openwifi.rrm.RRMAlgorithm;
import com.facebook.openwifi.rrm.RRMConfig;
import com.facebook.openwifi.rrm.VersionProvider;
import com.facebook.openwifi.rrm.mysql.DatabaseManager;
import com.facebook.openwifi.rrm.services.MockOWSecService;
import com.facebook.openwifi.rrm.Utils;
import com.google.gson.Gson;

import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;

/**
 * A class for testing ApiServer. In order to test auth logic, you must tag
 * the test "auth". Doing so will spin up a mock ow security service and
 * enable auth on the server.
 */
@TestMethodOrder(OrderAnnotation.class)
public class ApiServerTest {
	/** The test server port for external calls. */
	private static final int TEST_EXTERNAL_PORT =
		spark.Service.SPARK_DEFAULT_PORT;

	/** The test server port for internal calls. */
	private static final int TEST_INTERNAL_PORT =
		TEST_EXTERNAL_PORT + 1;

	/** The mock ow sec service port */
	private static final int TEST_OWSEC_PORT =
		TEST_INTERNAL_PORT + 1;

	/** Test device data manager. */
	private DeviceDataManager deviceDataManager;

	/** Test RRM config. */
	private RRMConfig rrmConfig;

	/** Test API server instance. */
	private ApiServer server;

	/** Mock OW sec service */
	private MockOWSecService owSecService;

	/** Test modeler instance. */
	private Modeler modeler;

	/** The Gson instance. */
	private final Gson gson = new Gson();

	/** Build an internal endpoint URL. */
	private String endpoint(String path) {
		return endpoint(path, true);
	}

	/** Build an endpoint URL. */
	private String endpoint(String path, boolean internal) {
		return String.format(
			"http://localhost:%d%s",
			internal ? TEST_INTERNAL_PORT : TEST_EXTERNAL_PORT,
			path
		);
	}

	@BeforeEach
	void setup(TestInfo testInfo) {
		this.deviceDataManager = new DeviceDataManager();

		// Create config
		this.rrmConfig = new RRMConfig();
		rrmConfig.moduleConfig.apiServerParams.internalHttpPort =
			TEST_INTERNAL_PORT;
		rrmConfig.moduleConfig.apiServerParams.externalHttpPort =
			TEST_EXTERNAL_PORT;

		UCentralClient client = null;

		boolean useAuth = testInfo.getTags().contains("auth");
		if (useAuth) {
			this.rrmConfig.moduleConfig.apiServerParams.useOpenWifiAuth = true;

			// spin up mock owsec service
			try {
				owSecService = new MockOWSecService(TEST_OWSEC_PORT);
			} catch (Exception e) {
				fail("Could not instantiate OWSec.", e);
			}

			// Create uCentral client with mock services as necessary
			client = new UCentralClient(
				"rrm",
				false,
				null,
				null,
				null,
				rrmConfig.uCentralConfig.uCentralSocketParams.connectTimeoutMs,
				rrmConfig.uCentralConfig.uCentralSocketParams.socketTimeoutMs,
				rrmConfig.uCentralConfig.uCentralSocketParams.wifiScanTimeoutMs
			);
			ServiceEvent owsecServiceEvent = new ServiceEvent();
			owsecServiceEvent.type = "owsec";
			owsecServiceEvent.privateEndPoint =
				String.format("http://localhost:%d", owSecService.getPort());
			client.setServiceEndpoint("owsec", owsecServiceEvent);
		}

		// Create clients (null for now)
		UCentralKafkaConsumer consumer = null;
		DatabaseManager dbManager = null;

		// Create scheduler
		RRMScheduler scheduler = new RRMScheduler(
			rrmConfig.moduleConfig.schedulerParams,
			deviceDataManager
		);

		// Instantiate dependent instances
		ConfigManager configManager = new ConfigManager(
			rrmConfig.moduleConfig.configManagerParams,
			deviceDataManager,
			client
		);
		DataCollector dataCollector = new DataCollector(
			rrmConfig.moduleConfig.dataCollectorParams,
			deviceDataManager,
			client,
			consumer,
			configManager,
			dbManager
		);
		this.modeler = new Modeler(
			rrmConfig.moduleConfig.modelerParams,
			deviceDataManager,
			consumer,
			client,
			dataCollector,
			configManager
		);

		// Instantiate ApiServer
		this.server = new ApiServer(
			rrmConfig.moduleConfig.apiServerParams,
			rrmConfig.serviceConfig,
			deviceDataManager,
			configManager,
			modeler,
			client,
			scheduler
		);
		try {
			server.run();
			server.awaitInitialization();
		} catch (Exception e) {
			fail("Could not instantiate ApiServer.", e);
		}
	}

	@AfterEach
	void tearDown() {
		// Destroy ApiServer
		if (server != null) {
			server.shutdown();
			server.awaitStop();
		}
		server = null;

		// reset owsec server
		if (owSecService != null) {
			owSecService.stop();
		}
		owSecService = null;

		// Reset Unirest client
		// Without this, Unirest randomly throws:
		// kong.unirest.UnirestException: java.net.SocketException: Software caused connection abort: recv failed
		Unirest.shutDown();
	}

	@Test
	@Order(1)
	void test_getTopology() throws Exception {
		final String validKey =
			Utils.generateServiceKey(rrmConfig.serviceConfig);

		// Create topology
		DeviceTopology topology = new DeviceTopology();
		topology.put("test-zone", new TreeSet<>(Arrays.asList("aaaaaaaaaaa")));
		deviceDataManager.setTopology(topology);

		// External listener cannot access topology, even with internal headers
		HttpResponse<String> resp = Unirest
			.get(endpoint("/api/v1/getTopology", false))
			.asString();
		assertEquals(403, resp.getStatus());
		resp = Unirest.get(endpoint("/api/v1/getTopology", false))
			.header("X-Forwarded-Host", "localhost:" + TEST_INTERNAL_PORT)
			.header("X-Forwarded-Port", String.valueOf(TEST_INTERNAL_PORT))
			.header("Forwarded", "host=localhost:" + TEST_INTERNAL_PORT)
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asString();
		assertEquals(403, resp.getStatus());

		// Internal listener still requires service credentials when auth is off
		resp = Unirest.get(endpoint("/api/v1/getTopology")).asString();
		assertEquals(403, resp.getStatus());
		resp = Unirest.get(endpoint("/api/v1/getTopology"))
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", "not_a_valid_key")
			.asString();
		assertEquals(403, resp.getStatus());

		// Fetch topology with valid internal service credentials
		resp = Unirest.get(endpoint("/api/v1/getTopology"))
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asString();
		assertEquals(200, resp.getStatus());
		assertEquals(deviceDataManager.getTopologyJson(), resp.getBody());
	}

	@Test
	@Order(2)
	void test_setTopology() throws Exception {
		String url = endpoint("/api/v1/setTopology");
		final String validKey =
			Utils.generateServiceKey(rrmConfig.serviceConfig);

		// Create existing and replacement topologies
		DeviceTopology existingTopology = new DeviceTopology();
		existingTopology.put(
			"existing-zone",
			new TreeSet<>(Arrays.asList("existing-ap"))
		);
		deviceDataManager.setTopology(existingTopology);
		String existingTopologyJson = deviceDataManager.getTopologyJson();

		DeviceTopology topology = new DeviceTopology();
		topology.put("test-zone", new TreeSet<>(Arrays.asList("aaaaaaaaaaa")));

		// Unauthorized requests cannot mutate topology when auth is disabled
		HttpResponse<String> resp = Unirest
			.post(url)
			.body(gson.toJson(topology))
			.asString();
		assertEquals(403, resp.getStatus());
		assertEquals(existingTopologyJson, deviceDataManager.getTopologyJson());
		assertEquals(
			"existing-zone",
			deviceDataManager.getZoneForSerial("existing-ap")
		);
		assertNull(deviceDataManager.getZoneForSerial("aaaaaaaaaaa"));

		resp = Unirest
			.post(url)
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", "not_a_valid_key")
			.body(gson.toJson(topology))
			.asString();
		assertEquals(403, resp.getStatus());
		assertEquals(existingTopologyJson, deviceDataManager.getTopologyJson());
		assertNull(deviceDataManager.getZoneForSerial("aaaaaaaaaaa"));

		resp = Unirest
			.post(endpoint("/api/v1/setTopology", false))
			.header("X-Forwarded-Host", "localhost:" + TEST_INTERNAL_PORT)
			.header("X-Forwarded-Port", String.valueOf(TEST_INTERNAL_PORT))
			.header("Forwarded", "host=localhost:" + TEST_INTERNAL_PORT)
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.body(gson.toJson(topology))
			.asString();
		assertEquals(403, resp.getStatus());
		assertEquals(existingTopologyJson, deviceDataManager.getTopologyJson());
		assertNull(deviceDataManager.getZoneForSerial("aaaaaaaaaaa"));

		// Set topology with valid internal service credentials
		resp = Unirest
			.post(url)
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.body(gson.toJson(topology))
			.asString();
		assertEquals(200, resp.getStatus());
		assertEquals(
			gson.toJson(topology),
			deviceDataManager.getTopologyJson()
		);

		// Missing/wrong parameters
		assertEquals(
			400,
			Unirest.post(url)
				.header("X-INTERNAL-NAME", "internal_name")
				.header("X-API-KEY", validKey)
				.body("not json")
				.asString()
				.getStatus()
		);

		String validTopologyJson = deviceDataManager.getTopologyJson();
		resp = Unirest.post(url)
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.body("{\"zone-1\":null}")
			.asString();
		assertEquals(400, resp.getStatus());
		assertEquals(
			"Device list cannot be null for zone 'zone-1'",
			resp.getBody()
		);
		assertEquals(validTopologyJson, deviceDataManager.getTopologyJson());
		assertEquals(
			"test-zone",
			deviceDataManager.getZoneForSerial("aaaaaaaaaaa")
		);
	}

	@Test
	@Order(3)
	void test_getDeviceZone() throws Exception {
		final String url = endpoint("/api/v1/device");
		final String zoneA = "Lobby";
		final String zoneB = "First Floor";
		final String deviceA1 = "AP-001";
		final String deviceA2 = "AP-002";
		final String deviceB1 = "AP-003";

		DeviceTopology topology = new DeviceTopology();
		topology.put(zoneA, new TreeSet<>(Arrays.asList(deviceA1, deviceA2)));
		topology.put(zoneB, new TreeSet<>(Arrays.asList(deviceB1)));
		deviceDataManager.setTopology(topology);

		HttpResponse<String> stringResp =
			Unirest.get(url + "/" + deviceA1 + "/zone").asString();
		assertEquals(403, stringResp.getStatus());

		final String validKey =
			Utils.generateServiceKey(rrmConfig.serviceConfig);
		HttpResponse<JsonNode> resp =
			Unirest.get(url + "/" + deviceA1 + "/zone")
				.header("X-INTERNAL-NAME", "internal_name")
				.header("X-API-KEY", validKey)
				.asJson();
		assertEquals(200, resp.getStatus());
		assertTrue(
			resp.getHeaders().getFirst("Content-Type").startsWith("application/json")
		);
		assertEquals(deviceA1, resp.getBody().getObject().getString("serialNumber"));
		assertEquals(zoneA, resp.getBody().getObject().getString("zone"));

		resp = Unirest.get(url + "/" + deviceA2 + "/zone")
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asJson();
		assertEquals(200, resp.getStatus());
		assertEquals(zoneA, resp.getBody().getObject().getString("zone"));

		DeviceTopology movedTopology = new DeviceTopology();
		movedTopology.put(zoneA, new TreeSet<>(Arrays.asList(deviceA1)));
		movedTopology.put(zoneB, new TreeSet<>(Arrays.asList(deviceA2, deviceB1)));
		deviceDataManager.setTopology(movedTopology);
		resp = Unirest.get(url + "/" + deviceA2 + "/zone")
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asJson();
		assertEquals(200, resp.getStatus());
		assertEquals(zoneB, resp.getBody().getObject().getString("zone"));

		resp = Unirest.get(url + "/UNKNOWN-AP/zone")
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asJson();
		assertEquals(404, resp.getStatus());
		assertEquals(
			"Zone not found",
			resp.getBody().getObject().getString("error")
		);

		resp = Unirest.get(url + "/%20/zone")
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asJson();
		assertEquals(400, resp.getStatus());
		assertEquals(
			"Invalid serial number",
			resp.getBody().getObject().getString("error")
		);

		resp = Unirest.get(url + "/%20" + deviceA1 + "%20/zone")
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asJson();
		assertEquals(400, resp.getStatus());
		assertEquals(
			"Invalid serial number",
			resp.getBody().getObject().getString("error")
		);

		String longSerial = new String(new char[65]).replace('\0', 'A');
		resp = Unirest.get(url + "/" + longSerial + "/zone")
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asJson();
		assertEquals(400, resp.getStatus());
		assertEquals(
			"Invalid serial number",
			resp.getBody().getObject().getString("error")
		);
	}

	@Test
	@Order(4)
	void test_getDeviceLayeredConfig() throws Exception {
		// Create topology and configs
		final String zone = "test-zone";
		final String ap = "aaaaaaaaaaa";
		DeviceTopology topology = new DeviceTopology();
		topology.put(zone, new TreeSet<>(Arrays.asList(ap)));
		deviceDataManager.setTopology(topology);
		DeviceLayeredConfig cfg = new DeviceLayeredConfig();
		cfg.networkConfig.enableRRM = true;
		DeviceConfig zoneConfig = new DeviceConfig();
		zoneConfig.enableWifiScan = false;
		cfg.zoneConfig.put(zone, zoneConfig);
		DeviceConfig apConfig = new DeviceConfig();
		apConfig.enableConfig = false;
		cfg.apConfig.put(ap, apConfig);
		deviceDataManager.setDeviceApConfig(ap, apConfig);

		// Fetch config
		HttpResponse<String> resp =
			Unirest.get(endpoint("/api/v1/getDeviceLayeredConfig")).asString();
		assertEquals(200, resp.getStatus());
		assertEquals(
			deviceDataManager.getDeviceLayeredConfigJson(),
			resp.getBody()
		);
	}

	@Test
	@Order(5)
	void test_getDeviceConfig() throws Exception {
		String url = endpoint("/api/v1/getDeviceConfig");

		// Create topology
		final String zone = "test-zone";
		final String ap = "aaaaaaaaaaa";
		DeviceTopology topology = new DeviceTopology();
		topology.put(zone, new TreeSet<>(Arrays.asList(ap)));
		deviceDataManager.setTopology(topology);

		// Fetch config
		HttpResponse<String> resp =
			Unirest.get(url + "?serial=" + ap).asString();
		assertEquals(200, resp.getStatus());
		String normalizedResp =
			gson.toJson(gson.fromJson(resp.getBody(), DeviceConfig.class));
		assertEquals(
			gson.toJson(deviceDataManager.getDeviceConfig(ap)),
			normalizedResp
		);

		// Missing/wrong parameters
		assertEquals(400, Unirest.get(url).asString().getStatus());
		assertEquals(
			400,
			Unirest.get(url + "?serial=asdf").asString().getStatus()
		);
	}

	@Test
	@Order(6)
	void test_setDeviceNetworkConfig() throws Exception {
		DeviceConfig config = new DeviceConfig();
		config.enableConfig = false;

		// Set config
		HttpResponse<String> resp = Unirest
			.post(endpoint("/api/v1/setDeviceNetworkConfig"))
			.body(gson.toJson(config))
			.asString();
		assertEquals(200, resp.getStatus());
		DeviceLayeredConfig fullCfg = gson.fromJson(
			deviceDataManager.getDeviceLayeredConfigJson(),
			DeviceLayeredConfig.class
		);
		assertEquals(gson.toJson(config), gson.toJson(fullCfg.networkConfig));
	}

	@Test
	@Order(7)
	void test_setDeviceZoneConfig() throws Exception {
		String url = endpoint("/api/v1/setDeviceZoneConfig");

		// Create topology
		final String zone = "test-zone";
		final String ap = "aaaaaaaaaaa";
		DeviceTopology topology = new DeviceTopology();
		topology.put(zone, new TreeSet<>(Arrays.asList(ap)));
		deviceDataManager.setTopology(topology);

		DeviceConfig config = new DeviceConfig();
		config.enableConfig = false;

		// Set config
		HttpResponse<String> resp = Unirest
			.post(url + "?zone=" + zone)
			.body(gson.toJson(config))
			.asString();
		assertEquals(200, resp.getStatus());
		DeviceLayeredConfig fullCfg = gson.fromJson(
			deviceDataManager.getDeviceLayeredConfigJson(),
			DeviceLayeredConfig.class
		);
		assertEquals(1, fullCfg.zoneConfig.size());
		assertTrue(fullCfg.zoneConfig.containsKey(zone));
		assertEquals(
			gson.toJson(config),
			gson.toJson(fullCfg.zoneConfig.get(zone))
		);

		// Missing/wrong parameters
		assertEquals(
			400,
			Unirest.post(url).body(gson.toJson(config)).asString().getStatus()
		);
		assertEquals(
			400,
			Unirest.post(url + "?zone=asdf").body(gson.toJson(config)).asString().getStatus()
		);
		assertEquals(
			400,
			Unirest.post(url + "?zone=" + zone).body("not json").asString().getStatus()
		);
	}

	@Test
	@Order(8)
	void test_setDeviceApConfig() throws Exception {
		String url = endpoint("/api/v1/setDeviceApConfig");

		// Create topology
		final String zone = "test-zone";
		final String ap = "aaaaaaaaaaa";
		DeviceTopology topology = new DeviceTopology();
		topology.put(zone, new TreeSet<>(Arrays.asList(ap)));
		deviceDataManager.setTopology(topology);

		DeviceConfig config = new DeviceConfig();
		config.enableConfig = false;

		// Set config
		HttpResponse<String> resp = Unirest
			.post(url + "?serial=" + ap)
			.body(gson.toJson(config))
			.asString();
		assertEquals(200, resp.getStatus());
		DeviceLayeredConfig fullCfg = gson.fromJson(
			deviceDataManager.getDeviceLayeredConfigJson(),
			DeviceLayeredConfig.class
		);
		assertEquals(1, fullCfg.apConfig.size());
		assertTrue(fullCfg.apConfig.containsKey(ap));
		assertEquals(
			gson.toJson(config),
			gson.toJson(fullCfg.apConfig.get(ap))
		);

		// Missing/wrong parameters
		assertEquals(
			400,
			Unirest.post(url).body(gson.toJson(config)).asString().getStatus()
		);
		assertEquals(
			400,
			Unirest.post(url + "?serial=asdf").body(gson.toJson(config)).asString().getStatus()
		);
		assertEquals(
			400,
			Unirest.post(url + "?serial=" + ap).body("not json").asString().getStatus()
		);
	}

	@Test
	@Order(9)
	void test_modifyDeviceApConfig() throws Exception {
		String url = endpoint("/api/v1/modifyDeviceApConfig");

		// Create topology
		final String zone = "test-zone";
		final String ap = "aaaaaaaaaaa";
		DeviceTopology topology = new DeviceTopology();
		topology.put(zone, new TreeSet<>(Arrays.asList(ap)));
		deviceDataManager.setTopology(topology);

		// Set initial AP config
		DeviceConfig apConfig = new DeviceConfig();
		apConfig.enableConfig = false;
		apConfig.autoChannels = new HashMap<>();
		apConfig.autoChannels.put(UCentralConstants.BAND_2G, 7);
		apConfig.autoChannels.put(UCentralConstants.BAND_5G, 165);
		deviceDataManager.setDeviceApConfig(ap, apConfig);

		// Construct config request
		DeviceConfig configReq = new DeviceConfig();
		configReq.enableConfig = true;
		configReq.autoTxPowers = new HashMap<>();
		configReq.autoTxPowers.put(UCentralConstants.BAND_2G, 20);
		configReq.autoTxPowers.put(UCentralConstants.BAND_5G, 28);

		// Merge config objects (expected result)
		apConfig.apply(configReq);

		// Set config
		HttpResponse<String> resp = Unirest
			.post(url + "?serial=" + ap)
			.body(gson.toJson(configReq))
			.asString();
		assertEquals(200, resp.getStatus());
		DeviceLayeredConfig fullCfg = gson.fromJson(
			deviceDataManager.getDeviceLayeredConfigJson(),
			DeviceLayeredConfig.class
		);
		assertTrue(fullCfg.apConfig.containsKey(ap));
		assertEquals(
			gson.toJson(apConfig),
			gson.toJson(fullCfg.apConfig.get(ap))
		);

		// Missing/wrong parameters
		assertEquals(
			400,
			Unirest.post(url).body(gson.toJson(configReq)).asString().getStatus()
		);
		assertEquals(
			400,
			Unirest.post(url + "?serial=asdf").body(gson.toJson(configReq)).asString().getStatus()
		);
		assertEquals(
			400,
			Unirest.post(url + "?serial=" + ap).body("not json").asString().getStatus()
		);
		assertEquals(
			400,
			Unirest.post(url + "?serial=" + ap).body("{}").asString().getStatus()
		);
	}

	@Test
	@Order(100)
	void test_currentModel() throws Exception {
		// Fetch RRM model
		HttpResponse<String> resp =
			Unirest.get(endpoint("/api/v1/currentModel")).asString();
		assertEquals(200, resp.getStatus());
		assertEquals(gson.toJson(modeler.getDataModel()), resp.getBody());
	}

	@Test
	@Order(101)
	void test_optimizeChannel() throws Exception {
		String url = endpoint("/api/v1/optimizeChannel");

		// Create topology
		final String zone = "test-zone";
		DeviceTopology topology = new DeviceTopology();
		topology.put(zone, new TreeSet<>(Arrays.asList("aaaaaaaaaaa")));
		deviceDataManager.setTopology(topology);

		// Correct requests
		final String[] modes =
			new String[] { "random", "least_used", "unmanaged_aware" };
		for (String mode : modes) {
			String endpoint =
				String.format("%s?mode=%s&zone=%s", url, mode, zone);
			HttpResponse<JsonNode> resp = Unirest.get(endpoint).asJson();
			assertEquals(200, resp.getStatus());
			assertNotNull(resp.getBody().getObject().getJSONObject("data"));
		}

		// Missing/wrong parameters
		assertEquals(400, Unirest.get(url).asString().getStatus());
		assertEquals(
			400,
			Unirest.get(url + "?mode=test123&zone=" + zone).asString().getStatus()
		);
		assertEquals(
			400,
			Unirest.get(url + "?zone=asdf&mode=" + modes[0]).asString().getStatus()
		);
	}

	@Test
	@Order(102)
	void test_optimizeTxPower() throws Exception {
		String url = endpoint("/api/v1/optimizeTxPower");

		// Create topology
		final String zone = "test-zone";
		DeviceTopology topology = new DeviceTopology();
		topology.put(zone, new TreeSet<>(Arrays.asList("aaaaaaaaaaa")));
		deviceDataManager.setTopology(topology);

		// Correct requests
		final String[] modes = new String[] { "random",
			"measure_ap_client",
			"measure_ap_ap",
			"location_optimal" };
		for (String mode : modes) {
			String endpoint =
				String.format("%s?mode=%s&zone=%s", url, mode, zone);
			HttpResponse<JsonNode> resp = Unirest.get(endpoint).asJson();
			assertEquals(200, resp.getStatus());
			assertNotNull(resp.getBody().getObject().getJSONObject("data"));
		}

		// Missing/wrong parameters
		assertEquals(400, Unirest.get(url).asString().getStatus());
		assertEquals(
			400,
			Unirest.get(url + "?mode=test123&zone=" + zone).asString().getStatus()
		);
		assertEquals(
			400,
			Unirest.get(url + "?zone=asdf&mode=" + modes[0]).asString().getStatus()
		);
	}

	@Test
	@Order(1000)
	void testDocs() throws Exception {
		// Index page paths
		assertEquals(
			200,
			Unirest.get(endpoint("/")).asString().getStatus()
		);
		assertEquals(
			200,
			Unirest.get(endpoint("/index.html")).asString().getStatus()
		);

		// OpenAPI YAML/JSON
		HttpResponse<String> yamlResp =
			Unirest.get(endpoint("/openapi.yaml")).asString();
		assertEquals(200, yamlResp.getStatus());
		HttpResponse<JsonNode> jsonResp =
			Unirest.get(endpoint("/openapi.json")).asJson();
		assertEquals(200, jsonResp.getStatus());
		// Check that we got the OpenAPI 3.x version string
		assertTrue(
			Arrays.stream(
				yamlResp.getBody().split("\\R+")
			)
				.filter(line -> line.matches("^openapi: 3[0-9.]*$"))
				.findFirst()
				.isPresent()
		);
		assertTrue(
			jsonResp.getBody().getObject().getString("openapi").matches("^3[0-9.]*$")
		);
		// Check that we got some endpoint paths
		JSONObject paths =
			jsonResp.getBody().getObject().getJSONObject("paths");
		assertFalse(paths.isEmpty());
		assertTrue(paths.keys().next().startsWith("/api/"));
		assertTrue(paths.has("/api/v1/device/{serialNumber}/zone"));
	}

	@Test
	@Order(1001)
	void test404() throws Exception {
		final String fakeEndpoint = endpoint("/test123");
		assertEquals(404, Unirest.get(fakeEndpoint).asString().getStatus());
		assertEquals(404, Unirest.post(fakeEndpoint).asString().getStatus());
		assertEquals(404, Unirest.put(fakeEndpoint).asString().getStatus());
		assertEquals(404, Unirest.delete(fakeEndpoint).asString().getStatus());
		assertEquals(404, Unirest.head(fakeEndpoint).asString().getStatus());
		assertEquals(404, Unirest.patch(fakeEndpoint).asString().getStatus());
	}

	@Test
	@Order(1002)
	void testCORS() throws Exception {
		final String fakeEndpoint = endpoint("/test123");
		final String HEADERS = "authorization";
		final String METHOD = "GET";
		final String ORIGIN = "https://example.com";
		HttpResponse<String> resp = Unirest.options(fakeEndpoint)
			.header("Access-Control-Request-Headers", HEADERS)
			.header("Access-Control-Request-Method", METHOD)
			.header("Origin", ORIGIN)
			.asString();
		assertEquals(200, resp.getStatus());
		assertEquals(
			ORIGIN,
			resp.getHeaders().getFirst("Access-Control-Allow-Origin")
		);
		assertEquals("Origin", resp.getHeaders().getFirst("Vary"));
		assertEquals(
			HEADERS,
			resp.getHeaders().getFirst("Access-Control-Allow-Headers")
		);
		assertEquals(
			METHOD,
			resp.getHeaders().getFirst("Access-Control-Allow-Methods")
		);
		assertEquals(
			"true",
			resp.getHeaders().getFirst("Access-Control-Allow-Credentials")
		);
		String maxAge = resp.getHeaders().getFirst("Access-Control-Max-Age");
		assertTrue(Integer.parseInt(maxAge) >= 0);
	}

	@Test
	@Order(2000)
	void test_system() throws Exception {
		// Test on GET api
		HttpResponse<JsonNode> get_resp =
			Unirest.get(endpoint("/api/v1/system?command=info")).asJson();
		assertEquals(200, get_resp.getStatus());
		assertEquals(
			VersionProvider.get(),
			get_resp.getBody().getObject().getString("version")
		);

		// Test on POST api
		String url = endpoint("/api/v1/system");
		// Valid command
		HttpResponse<String> post_resp = Unirest
			.post(url)
			.body("{\"command\": \"reload\"}")
			.asString();
		assertEquals(200, post_resp.getStatus());

		// Missing/wrong parameters
		assertEquals(
			400,
			Unirest.post(url).body("{\"command\": \"xxx\"}").asString().getStatus()
		);
		assertEquals(
			400,
			Unirest.post(url).body("{\"invalid command\": \"xxx\"}").asString().getStatus()
		);
	}

	@Test
	@Order(2001)
	void test_provider() throws Exception {
		HttpResponse<JsonNode> resp =
			Unirest.get(endpoint("/api/v1/provider")).asJson();
		assertEquals(200, resp.getStatus());
		assertEquals(
			rrmConfig.serviceConfig.vendor,
			resp.getBody().getObject().getString("vendor")
		);
		assertEquals(
			rrmConfig.serviceConfig.vendorUrl,
			resp.getBody().getObject().getString("about")
		);
		assertEquals(
			VersionProvider.get(),
			resp.getBody().getObject().getString("version")
		);
	}

	@Test
	@Order(2002)
	void test_algorithms() throws Exception {
		HttpResponse<JsonNode> resp =
			Unirest.get(endpoint("/api/v1/algorithms")).asJson();
		assertEquals(200, resp.getStatus());
		assertEquals(
			RRMAlgorithm.AlgorithmType.values().length,
			resp.getBody().getArray().length()
		);
	}

	@Test
	@Order(2003)
	void test_runRRM() throws Exception {
		String url = endpoint("/api/v1/runRRM");

		// Create topology
		final String zone = "test-zone";
		DeviceTopology topology = new DeviceTopology();
		topology.put(zone, new TreeSet<>(Arrays.asList("aaaaaaaaaaa")));
		deviceDataManager.setTopology(topology);

		// Correct requests
		final List<String> algorithms = Arrays
			.stream(RRMAlgorithm.AlgorithmType.values())
			.map(RRMAlgorithm.AlgorithmType::name)
			.collect(Collectors.toList());
		for (String name : algorithms) {
			String endpoint =
				String.format("%s?algorithm=%s&venue=%s", url, name, zone);
			HttpResponse<JsonNode> resp = Unirest.put(endpoint).asJson();
			assertEquals(200, resp.getStatus());
			assertFalse(resp.getBody().getObject().has("error"));
			assertEquals(1, resp.getBody().getObject().keySet().size());
		}

		// Missing/wrong parameters
		assertEquals(400, Unirest.put(url).asString().getStatus());
		assertEquals(
			400,
			Unirest.put(url + "?mode=test123&venue=" + zone).asString().getStatus()
		);
		assertEquals(
			400,
			Unirest.put(url + "?venue=asdf&algorithm=" + algorithms.get(0)).asString().getStatus()
		);
	}

	@Test
	@Tag("auth")
	@Order(3000)
	void test_publicEndpoint() throws Exception {
		// no token
		HttpResponse<String> resp =
			Unirest.get(endpoint("/api/v1/getTopology", false)).asString();
		assertEquals(403, resp.getStatus());
		resp = Unirest
			.get(endpoint("/api/v1/device/AP-001/zone", false))
			.asString();
		assertEquals(403, resp.getStatus());
		assertEquals(
			"Endpoint restricted to internal inter-service communication",
			new JSONObject(resp.getBody()).getString("error")
		);
		resp = Unirest
			.post(endpoint("/api/v1/setTopology", false))
			.body("{}")
			.asString();
		assertEquals(403, resp.getStatus());

		// bad token
		resp = Unirest.get(endpoint("/api/v1/getTopology", false))
			.header("Authorization", "Bearer some_bad_token")
			.asString();
		assertEquals(403, resp.getStatus());

		// valid for 300 seconds (5 minutes)
		String token = "this_is_a_good_token";
		owSecService.addToken(token, 300);
		DeviceTopology topology = new DeviceTopology();
		topology.put("test-zone", new TreeSet<>(Arrays.asList("AP-001")));
		deviceDataManager.setTopology(topology);
		String originalTopologyJson = deviceDataManager.getTopologyJson();
		DeviceTopology maliciousTopology = new DeviceTopology();
		maliciousTopology.put(
			"malicious-zone",
			new TreeSet<>(Arrays.asList("AP-MALICIOUS"))
		);
		// good token
		resp = Unirest.get(endpoint("/api/v1/getTopology", false))
			.header("Authorization", "Bearer " + token)
			.asString();
		assertEquals(403, resp.getStatus());
		// public endpoint cannot access internal-only device zone lookup, even with good token
		resp = Unirest
			.get(endpoint("/api/v1/device/AP-001/zone", false))
			.header("Authorization", "Bearer " + token)
			.asString();
		assertEquals(403, resp.getStatus());
		assertEquals(
			"Endpoint restricted to internal inter-service communication",
			new JSONObject(resp.getBody()).getString("error")
		);
		resp = Unirest
			.post(endpoint("/api/v1/setTopology", false))
			.header("Authorization", "Bearer " + token)
			.body(gson.toJson(maliciousTopology))
			.asString();
		assertEquals(403, resp.getStatus());
		assertEquals(originalTopologyJson, deviceDataManager.getTopologyJson());
		assertNull(deviceDataManager.getZoneForSerial("AP-MALICIOUS"));

		final String validKey =
			Utils.generateServiceKey(rrmConfig.serviceConfig);
		resp = Unirest
			.get(endpoint("/api/v1/getTopology", false))
			.header("X-Forwarded-Host", "localhost:" + TEST_INTERNAL_PORT)
			.header("X-Forwarded-Port", String.valueOf(TEST_INTERNAL_PORT))
			.header("Forwarded", "host=localhost:" + TEST_INTERNAL_PORT)
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asString();
		assertEquals(403, resp.getStatus());
		resp = Unirest
			.get(endpoint("/api/v1/device/AP-001/zone", false))
			.header("X-Forwarded-Host", "localhost:" + TEST_INTERNAL_PORT)
			.header("X-Forwarded-Port", String.valueOf(TEST_INTERNAL_PORT))
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asString();
		assertEquals(403, resp.getStatus());
		resp = Unirest
			.post(endpoint("/api/v1/setTopology", false))
			.header("X-Forwarded-Host", "localhost:" + TEST_INTERNAL_PORT)
			.header("X-Forwarded-Port", String.valueOf(TEST_INTERNAL_PORT))
			.header("Forwarded", "host=localhost:" + TEST_INTERNAL_PORT)
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.body(gson.toJson(maliciousTopology))
			.asString();
		assertEquals(403, resp.getStatus());
		assertEquals(originalTopologyJson, deviceDataManager.getTopologyJson());
		assertNull(deviceDataManager.getZoneForSerial("AP-MALICIOUS"));
	}

	@Test
	@Tag("auth")
	@Order(3001)
	void test_privateEndpoint() throws Exception {
		// no headers
		HttpResponse<String> resp =
			Unirest.get(endpoint("/api/v1/getTopology", true)).asString();
		assertEquals(403, resp.getStatus());
		resp = Unirest
			.get(endpoint("/api/v1/device/AP-001/zone", true))
			.asString();
		assertEquals(403, resp.getStatus());
		assertEquals(
			"Forbidden",
			new JSONObject(resp.getBody()).getString("error")
		);
		resp = Unirest
			.post(endpoint("/api/v1/setTopology", true))
			.body("{}")
			.asString();
		assertEquals(403, resp.getStatus());

		// bad headers
		resp = Unirest.get(endpoint("/api/v1/getTopology", true))
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", "not_a_valid_key")
			.asString();
		assertEquals(403, resp.getStatus());
		resp = Unirest.post(endpoint("/api/v1/setTopology", true))
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", "not_a_valid_key")
			.body("{}")
			.asString();
		assertEquals(403, resp.getStatus());

		// good headers
		final String validKey =
			Utils.generateServiceKey(rrmConfig.serviceConfig);
		DeviceTopology topology = new DeviceTopology();
		topology.put("test-zone", new TreeSet<>(Arrays.asList("AP-001")));
		deviceDataManager.setTopology(topology);

		resp = Unirest.get(endpoint("/api/v1/getTopology", true))
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asString();
		assertEquals(200, resp.getStatus());
		assertEquals(deviceDataManager.getTopologyJson(), resp.getBody());

		resp = Unirest.get(endpoint("/api/v1/device/AP-001/zone", true))
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.asString();
		assertEquals(200, resp.getStatus());

		DeviceTopology updatedTopology = new DeviceTopology();
		updatedTopology.put(
			"updated-zone",
			new TreeSet<>(Arrays.asList("AP-UPDATED"))
		);
		resp = Unirest.post(endpoint("/api/v1/setTopology", true))
			.header("X-INTERNAL-NAME", "internal_name")
			.header("X-API-KEY", validKey)
			.body(gson.toJson(updatedTopology))
			.asString();
		assertEquals(200, resp.getStatus());
		assertEquals(gson.toJson(updatedTopology), deviceDataManager.getTopologyJson());
	}
}
