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
 * Contributors:
 *     bdelbosc
 */
package org.nuxeo.importer.stream.consumer;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.blob.BlobInfo;
import org.nuxeo.ecm.core.redis.RedisAdmin;
import org.nuxeo.ecm.core.redis.RedisExecutor;
import org.nuxeo.importer.stream.message.DocumentMessage;
import org.nuxeo.lib.stream.pattern.consumer.AbstractConsumer;
import org.nuxeo.runtime.api.Framework;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Consumes DocumentMessage and send them to Redis which can be used as Gatling feeder.
 *
 * @since 10.1
 */
public class RedisDocumentMessageConsumer extends AbstractConsumer<DocumentMessage> {
    private static final Log log = LogFactory.getLog(RedisDocumentMessageConsumer.class);

    protected static final String DEFAULT_REDIS_PREFIX = "imp";

    protected static final String DOC_KEY_SUFFIX = "doc";

    protected static final String DATA_KEY_SUFFIX = "data";

    protected static final String FOLDER_KEY_SUFFIX = "folder";

    protected static final String BATCH_ID_TAG = "_BATCH_ID_";

    protected final byte[] addDocumentSHA;

    protected final RedisExecutor redisExecutor;

    protected final String redisPrefix;

    public RedisDocumentMessageConsumer(String consumerId, String redisPrefix) {
        super(consumerId);
        if (StringUtils.isBlank(redisPrefix)) {
            this.redisPrefix = DEFAULT_REDIS_PREFIX;
        } else {
            this.redisPrefix = redisPrefix;
        }
        RedisAdmin redisAdmin = Framework.getService(RedisAdmin.class);
        try {
            addDocumentSHA = redisAdmin.load("org.nuxeo.importer.stream", "add-document").getBytes("UTF-8");
        } catch (IOException e) {
            throw new NuxeoException("Cannot load Redis script", e);
        }
        redisExecutor = Framework.getService(RedisExecutor.class);
    }

    @Override
    public void accept(DocumentMessage message) {
        BlobInfo blobInfo = message.getBlobInfo();
        String blobPath = "";
        String blobFilename = "";
        String blobMimeType = "";
        String properties;
        try {
            if (blobInfo == null || StringUtils.isBlank(blobInfo.filename)) {
                properties = getProperties(message);
            } else {
                blobPath = blobInfo.filename;
                blobMimeType = blobInfo.mimeType;
                blobFilename = Paths.get(blobInfo.filename).getFileName().toString();
                properties = getPropertiesWithBlob(message);
            }
            String parentPath = message.getParentPath();
            if (parentPath.startsWith("/")) {
                parentPath = parentPath.substring(1);
            }
            String key = Paths.get(parentPath, message.getName()).toString();
            String docKey = redisPrefix + ":" + DOC_KEY_SUFFIX;
            String folderKey = redisPrefix + ":" + FOLDER_KEY_SUFFIX;
            String dataKey = redisPrefix + ":" + DATA_KEY_SUFFIX + ":" + key;
            String level = String.valueOf(key.split("/").length);
            String url = URLEncoder.encode(key, "UTF-8").replaceAll("%2F", "/");
            String payload = getPayload(message, properties);
            redisExecutor.evalsha(addDocumentSHA, Arrays.asList(bytes(docKey), bytes(dataKey), bytes(folderKey)),
                    Arrays.asList(bytes(key), bytes(parentPath), bytes(message.getType()), bytes(message.getName()),
                            bytes(payload), bytes(url), bytes(level), bytes(blobPath), bytes(blobFilename),
                            bytes(blobMimeType)));
        } catch (UnsupportedEncodingException e) {
            throw new NuxeoException("Cannot urlencode: " + message, e);
        } catch (JsonProcessingException e) {
            throw new NuxeoException("Cannot convert properties to json: " + message, e);
        }
    }

    protected String getPayload(DocumentMessage message, String properties) {
        return String.format("{\"entity-type\": \"document\", \"name\": \"%s\", \"type\": \"%s\", \"properties\": %s}",
                message.getName(), message.getType(), properties);
    }

    protected String getPropertiesWithBlob(DocumentMessage message) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode node = mapper.valueToTree(message.getProperties());
        ObjectNode fileContent = mapper.createObjectNode();
        // use a place holder for the batch id
        fileContent.put("upload-batch", BATCH_ID_TAG);
        fileContent.put("upload-fileId", "0");
        return ((ObjectNode) node).set("file:content", fileContent).toString();
    }

    protected String getProperties(DocumentMessage message) throws JsonProcessingException {
        return new ObjectMapper().writeValueAsString(message.getProperties());
    }

    protected byte[] bytes(String val) {
        try {
            return val.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new NuxeoException("Cannot get bytes: " + val, e);
        }
    }

    @Override
    public void begin() {

    }

    @Override
    public void commit() {

    }

    @Override
    public void rollback() {

    }

}
