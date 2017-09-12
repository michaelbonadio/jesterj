/*
 * Copyright 2016 Needham Software LLC
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
 */

package org.jesterj.ingest.processors;

import com.copyright.easiertest.SimpleProperty;
import org.apache.cassandra.utils.ConcurrentBiMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionWriteResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.jesterj.ingest.config.Required;
import org.jesterj.ingest.model.Document;
import org.jesterj.ingest.model.Status;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;

public class ElasticSender extends BatchProcessor<ActionRequest> {
  private static final Logger log = LogManager.getLogger();

  protected Client client;
  protected String indexName;
  protected String objectType;
  protected String name;
  private int connectTimout;

  @Override
  protected void individualFallbackOperation(ConcurrentBiMap<Document, ActionRequest> oldBatch, Exception e) {
    Map<ActionFuture, ActionRequest> futures = new HashMap<>();
    for (ActionRequest request : oldBatch.values()) {
      if (request instanceof UpdateRequest) {
        futures.put(getClient().update((UpdateRequest) request), request);
      } else if (request instanceof DeleteRequest) {
        futures.put(getClient().delete((DeleteRequest) request), request);
      } else if (request instanceof IndexRequest) {
        futures.put(getClient().index((IndexRequest) request), request);
      } else {
        throw new IllegalStateException("Should only have generated index, update and delete " +
            "actions, but found" + request.getClass());
      }
    }

    for (ActionFuture individualRetry : futures.keySet()) {
      handleRetryResult(e, futures, individualRetry, oldBatch);
    }
  }

  @Override
  public boolean hasExternalSideEffects() {
    return true;
  }

  void handleRetryResult(Exception e, Map<ActionFuture, ActionRequest> futures, ActionFuture individualRetry, ConcurrentBiMap<Document, ActionRequest> oldBatch) {
    ActionRequest request = futures.get(individualRetry);
    Document document = oldBatch.inverse().get(request);
    String id = document.getId();
    putIdInThreadContext(document);
    try {
      ActionWriteResponse resp = (ActionWriteResponse) individualRetry.actionGet();
      checkResponse(document, resp);
    } catch (Exception ex) {
      log.info(Status.ERROR.getMarker(), "{} could not be sent to elastic because of {}", id, ex.getMessage());
      log.error("Error sending to elastic!", e);
    }
  }

  void checkResponse(Document document, ActionWriteResponse resp) {
    ActionWriteResponse.ShardInfo shardInfo = resp.getShardInfo();
    if (shardInfo.status().getStatus() >= 400) {
      String id = document.getId();
      if (shardInfo.getSuccessful() == 0) {
        String simpleName = resp.getClass().getSimpleName();
        log.info(Status.ERROR.getMarker(),
            "{} failed {} for all applicable shards. For details check Elastic's logs", id, simpleName);
        log.error("{} failed {} for all applicable shards. For details check Elastic's logs", id, simpleName);
      } else {
        log.warn("{} failed on update for {} shards, check elastic logs for details", id, shardInfo.getFailed());
      }
    }
  }

  @Override
  protected void batchOperation(ConcurrentBiMap<Document, ActionRequest> oldBatch) throws Exception {
    BulkRequestBuilder builder = getClient().prepareBulk();
    for (ActionRequest request : oldBatch.values()) {
      if (request instanceof UpdateRequest) {
        builder.add((UpdateRequest) request);
      } else if (request instanceof DeleteRequest) {
        builder.add((DeleteRequest) request);
      } else if (request instanceof IndexRequest) {
        builder.add((IndexRequest) request);
      } else {
        throw new IllegalStateException("Should only have generated index, update and delete " +
            "actions, but found" + request.getClass());
      }
    }
    BulkResponse bulkResponse = builder.get();
    if (bulkResponse.hasFailures()) {
      throw new ESBulkFail();
    } else {
      for (Document doc : oldBatch.keySet()) {
        log.info("Successfully sent {} to elastic", doc.getId());
      }
    }
  }

  @Override
  protected void perDocFailLogging(Exception e, Document doc) {
    log.info(Status.ERROR.getMarker(), "{} could not be sent to elastic because of {}", doc.getId(), e.getMessage());
    log.error("Error communicating with elastic!", e);
  }

  @Override
  protected boolean exceptionIndicatesDocumentIssue(Exception e) {
    // TODO figure out what causes might be due to a single document vs not available etc
    return e instanceof ESBulkFail;
  }


  @Override
  protected ActionRequest convertDoc(Document document) {
    Document.Operation operation = document.getOperation();
    switch (operation) {
      case NEW: {
        IndexRequest indexRequest = new IndexRequest(getIndexName(), getObjectType(), document.getId());
        indexRequest.source(document.asMap());
        return indexRequest;
      }
      case UPDATE: {
        UpdateRequest updateRequest = new UpdateRequest(getIndexName(), getObjectType(), document.getId());
        updateRequest.doc(document.asMap());
        return updateRequest;
      }
      case DELETE: {
        return new DeleteRequest(getIndexName(), getObjectType(), document.getId());
      }
    }
    throw new UnsupportedOperationException("Operation was:" + operation);
  }

  @SimpleProperty
  protected String getIndexName() {
    return indexName;
  }

  @SimpleProperty
  protected void setIndexName(String indexName) {
    this.indexName = indexName;
  }

  @SimpleProperty
  protected String getObjectType() {
    return objectType;
  }

  @SimpleProperty
  protected void setObjectType(String objectType) {
    this.objectType = objectType;
  }

  @Override
  @SimpleProperty
  public String getName() {
    return name;
  }

  @SimpleProperty
  protected void setName(String name) {
    this.name = name;
  }

  // --- No Coverage for these props --- //  no ROI for testing these not so simple properties

  protected Client getClient() {
    return client;
  }

  protected void setClient(Client client) {
    this.client = client;
  }

  public int getConnectTimeout() {
    return connectTimout;
  }


  public static class Builder extends BatchProcessor.Builder {

    private Map<String, String> hosts = new HashMap<>();
    private ElasticSender obj = new ElasticSender();
    boolean connectSetting = false;

    protected  ElasticSender getObj() {
      return obj;
    }

    @Override
    public ElasticSender build() {
      ElasticSender obj = getObj();
      try {

        TransportClient.Builder clientBuilder = TransportClient.builder();
        if (connectSetting) {
          Settings.Builder settings = Settings.settingsBuilder();
          settings.put("transport.tcp.connect_timeout", obj.connectTimout + "ms");
          clientBuilder.settings(settings);
        }
        TransportClient transportClient = clientBuilder.build();
        for (Map.Entry<String, String> host : hosts.entrySet()) {
          int port = Integer.valueOf(host.getValue());
          transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getByName(host.getKey()), port));
        }
        obj.setClient(transportClient);
      } catch (UnknownHostException e) {
        log.error("Could not find elastic!", e);
        throw new RuntimeException(e);
      }
      return obj;
    }

    @Override
    public boolean isValid() {
      boolean nullHost = false;
      boolean nonIntegerPort = false;
      for (Map.Entry<String, String> host : hosts.entrySet()) {
        try {
          log.trace("{}:{}", host.getKey(), Integer.valueOf(host.getValue()));
        } catch (NumberFormatException nfe) {
          nonIntegerPort = true;
          log.error("Non-numeric port {} for host:{} in processor named {}",
              host.getValue(), host.getKey(), obj.getName());
        }
        if (host.getKey() == null) {
          nullHost = true;
          log.error("Null host in list of hosts for transportClient in processor named {}", obj.getName());
        }
      }
      if (hosts.size() == 0) {
        log.error("No hosts supplied for processor named {}", obj.getName());
      }
      if (obj.getConnectTimeout() < 0) {
        log.error("Negative timeout set for connecting to elastic?");
        return false;
      }
      return super.isValid() && getObj() != null && getObj().indexName != null &&
          getObj().objectType != null && !nonIntegerPort && !nullHost;
    }

    @Override
    public Builder named(String name) {
      getObj().name = name;
      return this;
    }

    @Required
    public Builder forIndex(String indexName) {
      getObj().indexName = indexName;
      return this;
    }

    @Required
    public Builder forObjectType(String objectType) {
      getObj().objectType = objectType;
      return this;
    }

    public Builder withServer(String host, Object port) {
      this.hosts.put(host, String.valueOf(port));
      return this;
    }

    public Builder withConnectTimout(int msTimeout) {
      getObj().connectTimout = msTimeout;
      connectSetting = true;
      return this;
    }

  }


  protected static class ESBulkFail extends RuntimeException {
  }
}
