// Copyright Vespa.ai. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.config.subscription;

import com.yahoo.config.ConfigInstance;
import com.yahoo.foo.*;

import org.junit.Test;

import static org.junit.Assert.*;
import static org.hamcrest.CoreMatchers.is;

/**
 * Tests ConfigSubscriber API, and the ConfigHandle class.
 *
 * @author Harald Musum
 */
public class ConfigApiTest {

    private static final String CONFIG_ID = "raw:" + "times 1\n";

    @Test
    public void testConfigSubscriber() {
        ConfigSubscriber subscriber = new ConfigSubscriber();
        ConfigHandle<AppConfig> h = subscriber.subscribe(AppConfig.class, CONFIG_ID);
        assertNotNull(h);
        subscriber.nextConfig(false);
        assertNotNull(h.getConfig());
        assertEquals(AppConfig.CONFIG_DEF_NAME, ConfigInstance.getDefName(h.getConfig().getClass()));
        assertTrue(h.isChanged());
        assertTrue(h.toString().startsWith("Handle changed: true\nSub:\n"));
        subscriber.close();
        assertEquals(ConfigSubscriber.State.CLOSED, subscriber.state());
    }

    /**
     * Verifies that we get an exception when trying to subscribe after close() has been called
     * for a ConfigSubscriber
     */
    @Test(expected = IllegalStateException.class)
    public void testSubscribeAfterClose() {
        ConfigSubscriber subscriber = new ConfigSubscriber();
        subscriber.subscribe(AppConfig.class, CONFIG_ID);
        subscriber.nextConfig(false);
        subscriber.close();
        subscriber.subscribe(AppConfig.class, CONFIG_ID);
    }

    /**
     * Verifies that it is not possible to to subscribe again after calling nextConfig()
     */
    @Test(expected = IllegalStateException.class)
    public void testSubscribeAfterNextConfig() {
        ConfigSubscriber subscriber = new ConfigSubscriber();
        subscriber.subscribe(AppConfig.class, CONFIG_ID);
        subscriber.nextConfig(false);
        subscriber.subscribe(AppConfig.class, CONFIG_ID);
        subscriber.close();
    }

}
