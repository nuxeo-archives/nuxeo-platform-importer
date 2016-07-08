/*
 * (C) Copyright 2016 Nuxeo SA (http://nuxeo.com/) and others.
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
 */
package org.nuxeo.ecm.platform.importer.queue;

import org.joda.time.DateTime;
import org.joda.time.Minutes;
import org.joda.time.Seconds;
import org.nuxeo.common.utils.ExceptionUtils;
import org.nuxeo.ecm.core.api.CoreInstance;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.platform.importer.filter.ImporterFilter;
import org.nuxeo.ecm.platform.importer.log.ImporterLogger;
import org.nuxeo.ecm.platform.importer.queue.consumer.Consumer;
import org.nuxeo.ecm.platform.importer.queue.consumer.ConsumerFactory;
import org.nuxeo.ecm.platform.importer.queue.consumer.ImportStat;
import org.nuxeo.ecm.platform.importer.queue.manager.QueuesManager;
import org.nuxeo.ecm.platform.importer.queue.producer.Producer;

import java.util.ArrayList;
import java.util.List;

/**
 * @since 8.3
 */
public class QueueImporter {

    protected ImporterLogger log = null;

    protected long processedNodesConsumer = 0L;

    protected ImportStat importStat = new ImportStat();

    protected long nbDocsCreated = 0L;

    protected List<ImporterFilter> filters = new ArrayList<ImporterFilter>();

    public QueueImporter(ImporterLogger log) {
        this.log = log;
    }

    public void importDocuments(Producer producer, QueuesManager manager, String importPath, String repositoryName,
                                int batchSize, ConsumerFactory factory) {
        log.info("importer: Starting import process");
        Exception finalException = null;
        DateTime importStarted = new DateTime();

        enableFilters();
        try (CoreSession session = CoreInstance.openCoreSessionSystem(repositoryName)){
            producer.init(manager);
            DocumentModel root = session.getDocument(new PathRef(importPath));

            startProducerThread(producer);
            List<Consumer> consumers = startConsumerPool(manager, root, batchSize, factory);
            finalException = waitForProducer(producer);
            consumersCanStop(consumers);
            finalException = waitForConsumers(consumers);
            updateStats(consumers, producer);

        } catch (Exception e) {
            log.error("Error while importing", e);
            finalException = e;
        } finally {
            disableFilters(finalException);
        }

        DateTime importFinished = new DateTime();
        log.info(String.format("import: End of process: producer send %d docs, consumer receive %d docs, creating %d docs with retry in %s mn, rate %.2f doc/s.",
                producer.getNbProcessed(), processedNodesConsumer, nbDocsCreated,
                Minutes.minutesBetween(importStarted, importFinished).getMinutes(),
                processedNodesConsumer/(float) Seconds.secondsBetween(importStarted, importFinished).getSeconds()));
    }

    private void updateStats(List<Consumer> consumers, Producer producer) {
        nbDocsCreated = 0;
        for (Consumer c : consumers) {
            processedNodesConsumer += c.getNbProcessed();
            nbDocsCreated += c.getNbDocsCreated();
            importStat.merge(c.getImportStat());
        }
        if (producer.getNbProcessed() != processedNodesConsumer) {
            log.error(
                    String.format("Producer produced %s nodes, Consumers processed %s nodes, some nodes have been lost",
                            producer.getNbProcessed(), processedNodesConsumer));
        }

    }

    private Exception waitForConsumers(List<Consumer> consumers) {
        Exception ret = null;
        try {
            while (!consumersTerminated(consumers)) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            log.error("importer: Got an InterruptedException", e);
            ret = e;
            ExceptionUtils.checkInterrupt(e);
        } finally {
            for (Consumer consumer : consumers) {
                if (!consumer.isTerminated()) {
                    log.warn("Forcibly stopping consumer");
                    consumer.mustStop();
                }
            }
        }
        int processed = 0;
        int i = 0;
        for (Consumer consumer : consumers) {
            processed += consumer.getNbProcessed();
            i += 1;
        }
        log.info("importer: " + i +  " consumers terminated, processed: " + processed );
        return ret;
    }

    private void consumersCanStop(List<Consumer> consumers) {
        for (Consumer c : consumers) {
            c.canStop();
        }
    }

    protected Exception waitForProducer(Producer producer) {
        Exception ret = null;
        try {
            while (!producer.isTerminated()) {
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            log.error("importer: Got an InterruptedException", e);
            ret = e;
            ExceptionUtils.checkInterrupt(e);
        } finally {
            if (!producer.isTerminated()) {
                log.warn("Forcibly stopping producer");
                producer.mustStop();
            }
        }
        log.info("importer: producer terminated processed: " + producer.getNbProcessed());
        return ret;
    }

    protected boolean consumersTerminated(List<Consumer> consumers) {
        for (Consumer c : consumers) {
            if (!c.isTerminated()) {
                return false;
            }
        }
        return true;
    }

    private List<Consumer> startConsumerPool(QueuesManager manager, DocumentModel root, int batchSize, ConsumerFactory factory) {
        ArrayList<Consumer> ret = new ArrayList<Consumer>(manager.getNBConsumers());
        for (int i = 0; i < manager.getNBConsumers(); i++) {
            Consumer c;
            c = factory.createConsumer(log, root, batchSize, manager.getQueue(i));
            ret.add(c);
            Thread ct = new Thread(c);
            ct.setName("import-Consumer" + i);
            ct.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {

                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    log.error("Uncaught exception in " + ct.getName() + ". Consumer is going to be stopped", e);
                    c.mustStop();
                }
            });
            ct.start();
        }
        return ret;
    }

    protected void startProducerThread(final Producer producer) {
        Thread p = new Thread(producer);
        p.setName("import-Producer");
        p.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {

            @Override
            public void uncaughtException(Thread t, Throwable e) {
                log.error("Uncaught exception in " + p.getName() + ". Producer is going to be stopped", e);
                producer.mustStop();
            }
        });
        p.start();
    }

    public ImportStat getImportStat() {
        return importStat;
    }

    public void addFilter(ImporterFilter filter) {
        log.debug(String.format("Filter with %s, was added on the importer with the hash code %s.", filter.toString(),
                hashCode()));
        filters.add(filter);
    }

    public long getCreatedDocsCounter() {
        return nbDocsCreated;
    }

    protected void enableFilters() {
        for (ImporterFilter filter : filters) {
            log.debug(String.format("Running filter with %s, on the importer with the hash code %s.", filter.toString(),
                    hashCode()));
            filter.handleBeforeImport();
        }
        if (filters.size() == 0) {
            log.debug(String.format("No filters are registered on the importer with hash code %s", hashCode()));
        }
    }

    protected void disableFilters(Exception finalException) {
        for (ImporterFilter filter : filters) {
            filter.handleAfterImport(finalException);
        }

    }

}
