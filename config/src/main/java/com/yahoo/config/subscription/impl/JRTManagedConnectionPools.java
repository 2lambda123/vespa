package com.yahoo.config.subscription.impl;

import com.yahoo.config.subscription.ConfigSourceSet;
import com.yahoo.vespa.config.JRTConnectionPool;
import com.yahoo.vespa.config.TimingValues;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class JRTManagedConnectionPools {
    private static class JRTSourceThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread t = new Thread(runnable, String.format("jrt-config-requester-%d", System.currentTimeMillis()));
            // We want a daemon thread to avoid hanging threads in case something goes wrong in the config system
            t.setDaemon(true);
            return t;
        }
    }
    private static class CountedPool {
        long count;
        final JRTConnectionPool pool;
        final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, new JRTSourceThreadFactory());
        CountedPool(JRTConnectionPool requester) {
            this.pool = requester;
            count = 1;
        }
    }
    private Map<ConfigSourceSet, CountedPool> pools = new HashMap<>();

    public synchronized JRTConfigRequester acquire(ConfigSourceSet sourceSet, TimingValues timingValues) {
        CountedPool countedPool = pools.get(sourceSet);
        if (countedPool == null) {
            countedPool = new CountedPool(new JRTConnectionPool(sourceSet));
            pools.put(sourceSet, countedPool);
        }
        countedPool.count++;
        return new JRTConfigRequester(sourceSet, countedPool.scheduler, countedPool.pool, timingValues);
    }
    public synchronized void release(ConfigSourceSet sourceSet) {
        CountedPool countedPool = pools.get(sourceSet);
        countedPool.count--;
        if (countedPool.count == 0) {
            countedPool.pool.close();
            countedPool.scheduler.shutdown();
           try {
               countedPool.scheduler.awaitTermination(30, TimeUnit.SECONDS);
           } catch (InterruptedException e) {
               throw new RuntimeException("Failed shutting down scheduler:", e);
           }
        }
    }
}
