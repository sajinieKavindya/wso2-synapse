package org.apache.synapse.transport.netty.api.config;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class WorkerPoolConfiguration {

    private static final Log log = LogFactory.getLog(WorkerPoolConfiguration.class);

    private int workerPoolCoreSize;
    private int workerPoolSizeMax;
    private int workerPoolThreadKeepAliveSec;
    private int workerPoolQueueLength;
    private String threadGroupID;
    private String threadID;

    public WorkerPoolConfiguration(String workerPoolCoreSize, String workerPoolSizeMax,
                                   String workerPoolThreadKeepAliveSec, String workerPoolQueueLength,
                                   String threadGroupID, String threadID) {

        try {
            if (workerPoolCoreSize != null && !"".equals(workerPoolCoreSize.trim())) {
                this.workerPoolCoreSize = Integer.parseInt(workerPoolCoreSize);
            }
            if (workerPoolSizeMax != null && !"".equals(workerPoolSizeMax.trim())) {
                this.workerPoolSizeMax = Integer.parseInt(workerPoolSizeMax);
            }
            if (workerPoolThreadKeepAliveSec != null && !"".equals(workerPoolThreadKeepAliveSec.trim())) {
                this.workerPoolThreadKeepAliveSec = Integer.parseInt(workerPoolThreadKeepAliveSec);
            }
            if (workerPoolQueueLength != null && !"".equals(workerPoolQueueLength.trim())) {
                this.workerPoolQueueLength = Integer.parseInt(workerPoolQueueLength);
            }
            if (threadGroupID != null && !"".equals(threadGroupID.trim())) {
                this.threadGroupID = threadGroupID;
            } else {
                this.threadGroupID = "Pass-Through Inbound WorkerThread Group";
            }
            if (threadID != null && !"".equals(threadID.trim())) {
                this.threadID = threadID;
            } else {
                this.threadID = "PassThroughInboundWorkerThread";
            }
        } catch (Exception e) {
            log.error("Please Provide int value for worker pool configuration", e);
        }
    }

    public int getWorkerPoolCoreSize() {
        return workerPoolCoreSize;
    }

    public int getWorkerPoolSizeMax() {
        return workerPoolSizeMax;
    }

    public int getWorkerPoolThreadKeepAliveSec() {
        return workerPoolThreadKeepAliveSec;
    }

    public String getThreadGroupID() {
        return threadGroupID;
    }

    public String getThreadID() {
        return threadID;
    }

    public int getWorkerPoolQueueLength() {
        return workerPoolQueueLength;
    }

}
