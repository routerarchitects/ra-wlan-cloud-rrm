/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.facebook.openwifi.rrm.services;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.facebook.openwifi.cloudsdk.UCentralClient;
import com.facebook.openwifi.cloudsdk.models.gw.ServiceEvent;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

/** Local HTTP stub; its synthetic responses are not deployed OWGW captures. */
public final class MockOWGWService implements AutoCloseable {
	private final HttpServer server;
	public volatile String responseBody = "{}";
	public volatile int responseStatus = 200;
	public volatile String requestPath;
	public volatile JsonObject requestBody;
	public volatile String apiKey;
	public volatile String internalName;
	public final AtomicInteger calls = new AtomicInteger();

	public MockOWGWService() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/v1/device/", exchange -> {
			requestPath = exchange.getRequestURI().getPath();
			requestBody = JsonParser.parseString(
				new String(
					exchange.getRequestBody().readAllBytes(),
					StandardCharsets.UTF_8
				)
			).getAsJsonObject();
			apiKey = exchange.getRequestHeaders().getFirst("X-API-KEY");
			internalName =
				exchange.getRequestHeaders().getFirst("X-INTERNAL-NAME");
			calls.incrementAndGet();
			byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders()
				.set("Content-Type", "application/json");
			exchange.sendResponseHeaders(responseStatus, bytes.length);
			try {
				exchange.getResponseBody().write(bytes);
			} finally {
				exchange.close();
			}
		});
		server.start();
	}

	public void connect(UCentralClient client) {
		ServiceEvent endpoint = new ServiceEvent();
		endpoint.type = "owgw";
		endpoint.key = "synthetic-gateway-key";
		endpoint.privateEndPoint =
			"http://127.0.0.1:" + server.getAddress().getPort();
		client.setServiceEndpoint("owgw", endpoint);
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
