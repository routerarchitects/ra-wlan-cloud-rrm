/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.rrm.modules;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.facebook.openwifi.cloudsdk.UCentralClient;
import com.facebook.openwifi.cloudsdk.UCentralUtils;
import com.facebook.openwifi.cloudsdk.models.gw.CommandInfo;
import com.facebook.openwifi.rrm.services.MockOWGWService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** SDK HTTP integration against a mock service; not an OWGW end-to-end test. */
class OWGWCommandIntegrationTest {
	@Test
	void syntheticAcknowledgmentsExerciseRealSdkTransportAndValidator()
		throws Exception {
		JsonArray cases;
		try (
			InputStreamReader reader = new InputStreamReader(
				getClass().getResourceAsStream("/owgw-command-responses.json"),
				StandardCharsets.UTF_8
			)
		) {
			cases = JsonParser.parseReader(reader)
				.getAsJsonObject()
				.getAsJsonArray("cases");
		}
		try (MockOWGWService gateway = new MockOWGWService()) {
			UCentralClient client = new UCentralClient(
				"rrm-test",
				false,
				null,
				null,
				null,
				1000,
				1000,
				1000
			);
			gateway.connect(client);
			for (JsonElement element : cases) {
				JsonObject fixture = element.getAsJsonObject();
				gateway.responseBody = fixture.get("response").toString();
				gateway.responseStatus = fixture.get("httpStatus").getAsInt();
				CommandInfo result =
					client.wifiScan(TestWifiClient.SERIAL, false);
				assertEquals(
					fixture.get("accepted").getAsBoolean(),
					UCentralUtils.isCommandAccepted(
						result,
						TestWifiClient.SERIAL,
						"wifiscan"
					),
					fixture.get("name").getAsString()
				);
				assertEquals(
					"/api/v1/device/" + TestWifiClient.SERIAL + "/wifiscan",
					gateway.requestPath
				);
				assertEquals(
					TestWifiClient.SERIAL,
					gateway.requestBody.get("serialNumber").getAsString()
				);
				assertFalse(gateway.requestBody.get("verbose").getAsBoolean());
				assertEquals("synthetic-gateway-key", gateway.apiKey);
				assertEquals("rrm-test", gateway.internalName);
			}
			assertEquals(cases.size(), gateway.calls.get());
		}
	}
}
