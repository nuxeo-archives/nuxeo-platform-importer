/*
 * (C) Copyright 2015 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Benoit Delbosc
 */
package org.nuxeo.importer.stream.automation;

import static org.nuxeo.importer.stream.automation.RandomBlobProducers.DEFAULT_BLOB_LOG_NAME;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.importer.stream.consumer.BlobInfoWriter;
import org.nuxeo.importer.stream.consumer.BlobMessageConsumerFactory;
import org.nuxeo.importer.stream.consumer.LogBlobInfoWriter;
import org.nuxeo.importer.stream.message.BlobMessage;
import org.nuxeo.lib.stream.log.LogManager;
import org.nuxeo.lib.stream.pattern.consumer.BatchPolicy;
import org.nuxeo.lib.stream.pattern.consumer.ConsumerPolicy;
import org.nuxeo.lib.stream.pattern.consumer.ConsumerPool;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.stream.StreamService;

import net.jodah.failsafe.RetryPolicy;

/**
 * @since 9.1
 */
@Operation(id = BlobConsumers.ID, category = Constants.CAT_SERVICES, label = "Import blobs", since = "9.1", description = "Import blob into the binarystore.")
public class BlobConsumers {
    private static final Log log = LogFactory.getLog(BlobConsumers.class);

    public static final String ID = "StreamImporter.runBlobConsumers";

    public static final String DEFAULT_LOG_BLOB_INFO_NAME = "blob-info";

    public static final String DEFAULT_LOG_CONFIG = "import";

    @Context
    protected OperationContext ctx;

    @Param(name = "nbThreads", required = false)
    protected Integer nbThreads;

    @Param(name = "blobProviderName", required = false)
    protected String blobProviderName = "default";

    @Param(name = "batchSize", required = false)
    protected Integer batchSize = 10;

    @Param(name = "batchThresholdS", required = false)
    protected Integer batchThresholdS = 20;

    @Param(name = "retryMax", required = false)
    protected Integer retryMax = 3;

    @Param(name = "retryDelayS", required = false)
    protected Integer retryDelayS = 2;

    @Param(name = "logName", required = false)
    protected String logName;

    @Param(name = "logBlobInfo", required = false)
    protected String logBlobInfoName;

    @Param(name = "logConfig", required = false)
    protected String logConfig;

    @Param(name = "waitMessageTimeoutSeconds", required = false)
    protected Integer waitMessageTimeoutSeconds = 20;

    @OperationMethod
    public void run() {
        RandomBlobProducers.checkAccess(ctx);
        ConsumerPolicy consumerPolicy = ConsumerPolicy.builder()
                                                      .name(ID)
                                                      // we set the batch policy but batch is not used by the blob
                                                      // consumer
                                                      .batchPolicy(
                                                              BatchPolicy.builder()
                                                                         .capacity(batchSize)
                                                                         .timeThreshold(
                                                                                 Duration.ofSeconds(batchThresholdS))
                                                                         .build())
                                                      .retryPolicy(new RetryPolicy().withMaxRetries(retryMax).withDelay(
                                                              retryDelayS, TimeUnit.SECONDS))
                                                      .maxThreads(getNbThreads())
                                                      .waitMessageTimeout(Duration.ofSeconds(waitMessageTimeoutSeconds))
                                                      .build();
        StreamService service = Framework.getService(StreamService.class);
        LogManager manager = service.getLogManager(getLogConfig());
        try (BlobInfoWriter blobInfoWriter = getBlobInfoWriter(manager)) {
            ConsumerPool<BlobMessage> consumers = new ConsumerPool<>(getLogName(), manager,
                    new BlobMessageConsumerFactory(blobProviderName, blobInfoWriter), consumerPolicy);
            consumers.start().get();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
    }

    protected BlobInfoWriter getBlobInfoWriter(LogManager managerBlobInfo) {
        initBlobInfoMQ(managerBlobInfo);
        return new LogBlobInfoWriter(managerBlobInfo.getAppender(getLogBlobInfoName()));
    }

    protected void initBlobInfoMQ(LogManager manager) {
        manager.createIfNotExists(getLogBlobInfoName(), 1);
    }

    protected short getNbThreads() {
        if (nbThreads != null) {
            return nbThreads.shortValue();
        }
        return 0;
    }

    protected String getLogName() {
        if (logName != null) {
            return logName;
        }
        return DEFAULT_BLOB_LOG_NAME;
    }

    protected String getLogBlobInfoName() {
        if (logBlobInfoName != null) {
            return logBlobInfoName;
        }
        return DEFAULT_LOG_BLOB_INFO_NAME;
    }

    protected String getLogConfig() {
        if (logConfig != null) {
            return logConfig;
        }
        return DEFAULT_LOG_CONFIG;
    }
}
