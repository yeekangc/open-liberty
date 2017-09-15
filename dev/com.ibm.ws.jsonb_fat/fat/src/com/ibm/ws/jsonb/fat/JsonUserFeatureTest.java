/*******************************************************************************
 * Copyright (c) 2017 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package com.ibm.ws.jsonb.fat;

import static com.ibm.ws.jsonb.fat.FATSuite.PROVIDER_JOHNZON_JSONP;
import static com.ibm.ws.jsonb.fat.FATSuite.PROVIDER_YASSON;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.ibm.websphere.simplicity.config.ServerConfiguration;

import componenttest.annotation.MinimumJavaLevel;
import componenttest.annotation.Server;
import componenttest.custom.junit.runner.FATRunner;
import componenttest.topology.impl.LibertyServer;
import componenttest.topology.utils.FATServletClient;

/**
 * This test for JSON-P is placed in the JSON-B bucket because it is convenient to access the Johnzon library here.
 * Consider if we should move to the JSON-P bucket once that is written.
 */
@RunWith(FATRunner.class)
@MinimumJavaLevel(javaLevel = 1.8)
public class JsonUserFeatureTest extends FATServletClient {

    @Server("com.ibm.ws.jsonp.container.userfeature.fat")
    public static LibertyServer server;

    @BeforeClass
    public static void setUp() throws Exception {
        server.startServer();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        server.stopServer("CWWKE0701E.*ServiceThatRequiresJsonp"); // TODO Remove once JSONP-1.1 spec regression to javax.json.JsonValue is fixed
        // Loading of JsonValue interface is trying to depend on presence of a provider:
        // javax.json.JsonException: Provider org.glassfish.json.JsonProviderImpl not found
        //   at javax.json.spi.JsonProvider.provider(JsonProvider.java:99)
        //   at javax.json.Json.createObjectBuilder(Json.java:299)
        //   at javax.json.JsonValue.<clinit>(JsonValue.java:61)
    }

    // Test a user feature with a service component that injects JsonProvider (from the bell)
    // as a declarative service. Validate the expected provider is used.
    @Test
    public void testJsonpFromUserFeature() throws Exception {
        String found;
        server.resetLogMarks();
        assertNotNull(found = server.waitForStringInLogUsingMark("TEST3: JsonProvider obtained from declarative services"));
        assertTrue(found, found.contains(PROVIDER_JOHNZON_JSONP));
        // TODO Enable once JSONP-1.1 spec regression (referenced earlier) is fixed
        //assertNotNull(found = server.waitForStringInLogUsingMark("TEST4"));
        //assertTrue(found, found.contains("\"weight\""));
        //assertTrue(found, found.contains("171"));
    }

    @Test
    public void testJsonbFromUserFeature() throws Exception {
        // Add the jsonb user feature, which will make 'ServiceThatRequiresJsonb' activate
        server.setMarkToEndOfLog();
        ServerConfiguration config = server.getServerConfiguration();
        config.getFeatureManager().getFeatures().add("usr:testFeatureUsingJsonb-1.0");
        server.updateServerConfiguration(config);
        server.waitForConfigUpdateInLogUsingMark(Collections.emptySet());

        // Scrape messages.log to verify that 'ServiceThatRequiresJsonb' has activated
        // using Johnzon for jsonp and Yasson for jsonb
        String found;
        assertNotNull(found = server.waitForStringInLogUsingMark("TEST1: JsonbProvider obtained from declarative services"));
        assertTrue(found, found.contains(PROVIDER_YASSON));
        assertNotNull(found = server.waitForStringInLogUsingMark("TEST1.1: JsonProvider obtained from declarative services"));
        assertTrue(found, found.contains(PROVIDER_JOHNZON_JSONP));
        assertNotNull(found = server.waitForStringInLogUsingMark("TEST2"));
        assertTrue(found, found.contains("success"));
        assertTrue(found, found.contains("\"Rochester\""));
        assertTrue(found, found.contains("\"Minnesota\""));
        assertTrue(found, found.contains("55901"));
        assertTrue(found, found.contains("410"));

        // Clean up the test by removing the jsonb-1.0 feature
        config.getFeatureManager().getFeatures().remove("usr:testFeatureUsingJsonb-1.0");
        server.updateServerConfiguration(config);
        server.waitForConfigUpdateInLogUsingMark(Collections.emptySet());
    }
}