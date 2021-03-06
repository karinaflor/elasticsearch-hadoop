package org.elasticsearch.hadoop.rest.pooling;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.elasticsearch.hadoop.EsHadoopException;
import org.elasticsearch.hadoop.EsHadoopIllegalArgumentException;
import org.elasticsearch.hadoop.cfg.Settings;
import org.elasticsearch.hadoop.rest.Transport;
import org.elasticsearch.hadoop.rest.TransportFactory;
import org.elasticsearch.hadoop.util.SettingsUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates HTTP Transports that are backed by a pool of Transport objects for reuse.
 */
final class PooledHttpTransportFactory implements TransportFactory {

    private final Log log = LogFactory.getLog(this.getClass());
    private final Map<String, TransportPool> hostPools = new HashMap<String, TransportPool>();
    private final String jobKey;

    PooledHttpTransportFactory(String jobKey) {
        this.jobKey = jobKey;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized Transport create(Settings settings, String hostInfo) {
        // Make sure that the caller's Settings has the correct job pool key.
        assertCorrectJobId(settings);
        return borrowFrom(getOrCreateTransportPool(hostInfo, settings), hostInfo);
    }

    /**
     * Checks to ensure that the caller is using a settings object with the same job id
     * that this pool is responsible for.
     * @param settings To be checked
     */
    private void assertCorrectJobId(Settings settings) {
        SettingsUtils.ensureJobTransportPoolingKey(settings);
        String requestingJobKey = SettingsUtils.getJobTransportPoolingKey(settings);
        if (!jobKey.equals(requestingJobKey)) {
            throw new EsHadoopIllegalArgumentException("Settings object passed does not have the same job " +
                    "pooling key property as when this pool was created. Job key requested was [" +
                    requestingJobKey + "] but this pool services job [" + jobKey + "]. This could be a " +
                    "different job incorrectly polluting the TransportPool. Bailing out...");
        }
    }

    /**
     * Gets the transport pool for the given host info, or creates one if it is absent.
     * @param hostInfo To get a pool for
     * @param settings For creating the pool if it does not exist
     * @return A transport pool for the given host
     */
    private TransportPool getOrCreateTransportPool(String hostInfo, Settings settings) {
        TransportPool pool;
        pool = hostPools.get(hostInfo); // Check again in case it was added while waiting for the lock
        if (pool == null) {
            pool = new TransportPool(jobKey, hostInfo, settings);
            hostPools.put(hostInfo, pool);
            if (log.isDebugEnabled()) {
                log.debug("Creating new TransportPool for job ["+jobKey+"] for host ["+hostInfo+"]");
            }
        }
        return pool;
    }

    /**
     * Creates a Transport using the given TransportPool.
     * @param pool Transport is borrowed from
     * @param hostInfo For logging purposes
     * @return A Transport backed by a pooled resource
     */
    private Transport borrowFrom(TransportPool pool, String hostInfo) {
        if (!pool.getJobPoolingKey().equals(jobKey)) {
            throw new EsHadoopIllegalArgumentException("PooledTransportFactory found a pool with a different owner than this job. This could be a different job incorrectly polluting the TransportPool. Bailing out...");
        }
        try {
            return pool.borrowTransport();
        } catch (Exception e) {
            throw new EsHadoopException(String.format("Could not get a Transport from the Transport Pool for host [%s]", hostInfo));
        }
    }

    /**
     * Iterates over the available host pools and asks each one to purge transports older than a certain age.
     * @return Total number of pooled connections still alive in this factory.
     */
    synchronized int cleanPools() {
        int totalConnectionsRemaining = 0;
        List<String> hostsToRemove = new ArrayList<String>();
        for (Map.Entry<String, TransportPool> hostPool : hostPools.entrySet()) {
            String host = hostPool.getKey();
            TransportPool pool = hostPool.getValue();

            int connectionsRemaining = pool.removeOldConnections();
            if (connectionsRemaining == 0) {
                hostsToRemove.add(host);
            } else {
                totalConnectionsRemaining += connectionsRemaining;
            }
        }

        // Remove old pools that now have no connections.
        for (String hostToRemove : hostsToRemove) {
            hostPools.remove(hostToRemove);
        }

        return totalConnectionsRemaining;
    }
}
