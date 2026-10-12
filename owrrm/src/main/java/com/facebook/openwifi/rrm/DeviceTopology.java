/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.rrm;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * AP topology model, mapping from "RF zone" name to a set of APs by serial
 * number.
 */
public class DeviceTopology extends TreeMap<String, Set<String>> {
	private static final long serialVersionUID = -1636132862513920700L;

	public DeviceTopology() {}

	public DeviceTopology(DeviceTopology source) {
		for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
			put(entry.getKey(), new TreeSet<>(entry.getValue()));
		}
	}

	@Hidden /* prevent Jackson object mapper from generating "empty" property */
	@Override
	public boolean isEmpty() { return super.isEmpty(); }
}
