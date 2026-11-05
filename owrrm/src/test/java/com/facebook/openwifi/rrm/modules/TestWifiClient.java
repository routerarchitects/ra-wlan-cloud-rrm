/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.rrm.modules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.facebook.openwifi.cloudsdk.UCentralClient;
import com.facebook.openwifi.cloudsdk.models.gw.CommandInfo;
import com.facebook.openwifi.cloudsdk.models.gw.DeviceCapabilities;
import com.facebook.openwifi.cloudsdk.models.gw.DeviceWithStatus;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

/** Controllable OWGW client shared by delivery and HTTP contract tests. */
class TestWifiClient extends UCentralClient {
	static final String SERIAL = "dc62796520cd";
	static final Gson GSON = new Gson();
	List<DeviceWithStatus> devices;
	DeviceCapabilities capabilities;
	CommandInfo scanResult = accepted("wifiscan");
	CommandInfo configResult = accepted("configure");
	RuntimeException scanFailure;
	RuntimeException capabilitiesFailure;
	RuntimeException configFailure;
	int capabilitiesCalls;
	int scanCalls;
	boolean scanVerbose;
	/** Exercise the actual SDK transport against the local mock gateway. */
	boolean useHttpCommands;
	final List<String> configurations = new ArrayList<>();

	TestWifiClient() {
		super("rrm", false, null, null, null, 1000, 1000, 1000);
		DeviceWithStatus device = new DeviceWithStatus();
		device.serialNumber = SERIAL;
		device.connected = true;
		device.configuration = GSON.fromJson(
			"{\"radios\":[" +
				"{\"band\":\"2G\",\"country\":\"US\",\"channel\":6,\"channel-width\":20,\"tx-power\":17}," +
				"{\"band\":\"5G\",\"country\":\"US\",\"channel\":36,\"channel-width\":80,\"tx-power\":23}]}",
			JsonObject.class
		);
		devices = Collections.singletonList(device);
		capabilities = GSON.fromJson(
			"{\"capabilities\":{\"wifi\":{\"phy0\":{" +
				"\"band\":[\"2G\"],\"htmode\":[\"HT20\",\"HT40\"],\"country\":\"US\"," +
				"\"channels\":[1,2,3,4,5,6,7,8,9,10,11]}}}}",
			DeviceCapabilities.class
		);
	}

	static CommandInfo accepted(String command) {
		CommandInfo result = new CommandInfo();
		result.UUID = "test-command";
		result.serialNumber = SERIAL;
		result.command = command;
		result.submitted = 100;
		result.status = "pending";
		return result;
	}

	@Override
	public boolean isInitialized() { return true; }

	@Override
	public List<DeviceWithStatus> getDevices() { return devices; }

	@Override
	public DeviceCapabilities getCapabilities(String serial) {
		capabilitiesCalls++;
		if (capabilitiesFailure != null) {
			throw capabilitiesFailure;
		}
		return capabilities;
	}

	@Override
	public CommandInfo wifiScan(String serial, boolean verbose) {
		scanCalls++;
		scanVerbose = verbose;
		if (scanFailure != null) {
			throw scanFailure;
		}
		if (useHttpCommands) {
			return super.wifiScan(serial, verbose);
		}
		return scanResult;
	}

	@Override
	public CommandInfo configure(String serial, String configuration) {
		configurations.add(configuration);
		if (configFailure != null) {
			throw configFailure;
		}
		if (useHttpCommands) {
			return super.configure(serial, configuration);
		}
		return configResult;
	}
}
