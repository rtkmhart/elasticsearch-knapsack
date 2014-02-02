
package org.xbib.elasticsearch.action;

import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.XContentRestResponse;
import org.elasticsearch.rest.XContentThrowableRestResponse;

import org.xbib.elasticsearch.plugin.knapsack.KnapsackHelper;
import org.xbib.elasticsearch.plugin.knapsack.KnapsackPacket;
import org.xbib.elasticsearch.plugin.knapsack.KnapsackStatus;
import org.xbib.elasticsearch.support.client.bulk.BulkClient;
import org.xbib.io.Connection;
import org.xbib.io.ConnectionFactory;
import org.xbib.io.ConnectionService;
import org.xbib.io.Packet;
import org.xbib.io.Session;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.util.Date;
import java.util.Map;

import static org.elasticsearch.client.Requests.createIndexRequest;
import static org.elasticsearch.common.collect.Maps.newHashMap;
import static org.elasticsearch.common.collect.Maps.newLinkedHashMap;
import static org.elasticsearch.common.xcontent.ToXContent.EMPTY_PARAMS;
import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.rest.RestStatus.OK;
import static org.elasticsearch.rest.action.support.RestXContentBuilder.restContentBuilder;

/**
 * The knapsack import action opens an archive and transfers the content into Elasticsearch
 */
public class RestImportAction extends BaseRestHandler {

    private final ClusterName clusterName;

    private final KnapsackHelper knapsackHelper;

    @Inject
    public RestImportAction(Settings settings,
                            Client client,
                            RestController controller,
                            ClusterService clusterService,
                            ClusterName clusterName) {
        super(settings, client);
        this.clusterName = clusterName;
        this.knapsackHelper = new KnapsackHelper(client, clusterService);

        controller.registerHandler(POST, "/_import", this);
        controller.registerHandler(POST, "/{index}/_import", this);
        controller.registerHandler(POST, "/{index}/{type}/_import", this);

        controller.registerHandler(POST, "/_import/{mode}", this);
        controller.registerHandler(POST, "/{index}/_import/{mode}", this);
        controller.registerHandler(POST, "/{index}/{type}/_import/{mode}", this);

        controller.registerHandler(GET, "/_import/state", new RestKnapsackImportState());
    }

    @Override
    public void handleRequest(final RestRequest request, RestChannel channel) {
        try {
            ConnectionService<Session<Packet<String>>> service = ConnectionService.getInstance();
            final String newIndex = request.param("index", "_all");
            final String newType = request.param("type");
            final String defaultSpec = newIndex + (newType != null ? "_" + newType : "") + ".tar.gz";
            final String path = request.param("path", defaultSpec);
            URI pathURI = URI.create("file:" + path);
            if ("s3".equals(request.param("mode")) && request.param("s3") != null && !new File(path).exists()) {
                // fetch file from S3
                URI s3 = URI.create(request.param("s3")
                        + "?bucketName=" + request.param("bucketName")
                        + "&key=" + request.param("key")
                        + "&path=" + path);
                logger.info("trying to transfer file from s3 to: {}", s3);
                ConnectionFactory<Session<Packet<String>>> factory = service.getConnectionFactory(s3);
                final Connection<Session<Packet<String>>> connection = factory.getConnection(s3);
                final Session session = connection.createSession();
                session.open(Session.Mode.READ);
                session.read(); // this will copy the file from S3 to path
                session.close();
                logger.info("file from s3 succesfully transferred");
            }
            ConnectionFactory<Session<Packet<String>>> factory = service.getConnectionFactory(pathURI);
            final Connection<Session<Packet<String>>> connection = factory.getConnection(pathURI);
            final Session<Packet<String>> session = connection.createSession();
            session.open(Session.Mode.READ);
            XContentBuilder builder = restContentBuilder(request);
            if (session.isOpen()) {
                EsExecutors.daemonThreadFactory(settings, "knapsack-import-[" + pathURI + "]")
                        .newThread(new ImportThread(request, connection, session)).start();
                builder.startObject()
                        .field("running", true)
                        .field("mode", request.param("mode") != null ? request.param("mode") : "import")
                        .field("type", factory.getName())
                        .field("path", connection.getURI())
                        .endObject();
            } else {
                builder.startObject()
                        .field("running", false)
                        .endObject();
            }
            channel.sendResponse(new XContentRestResponse(request, OK, builder));
        } catch (Throwable ex) {
            try {
                logger.error(ex.getMessage(), ex);
                channel.sendResponse(new XContentThrowableRestResponse(request, ex));
            } catch (Exception ex2) {
                logger.error(ex2.getMessage(), ex2);
            }
        }
    }

    private class ImportThread extends Thread {

        private final RestRequest request;

        private final Map<String, CreateIndexRequest> indexRequestMap = newHashMap();

        private final Connection<Session<Packet<String>>> connection;

        private final Session<Packet<String>> session;

        private TimeValue timeout;

        private BulkClient bulkClient;

        private Map<String,Object> map;

        private Map<String, Packet<String>> packets;

        ImportThread(RestRequest request, Connection<Session<Packet<String>>> connection, Session<Packet<String>> session) {
            this.request = request;
            this.connection = connection;
            this.session = session;
        }

        @Override
        public void run() {
            this.bulkClient = new BulkClient();
            final Map<String, String> params = request.params();
            logger.info("params = {}", params);
            this.timeout = request.paramAsTime("timeout", TimeValue.timeValueSeconds(30L));
            final int maxActionsPerBulkRequest = request.paramAsInt("maxActionsPerBulkRequest", 1000);
            final int maxBulkConcurrency = request.paramAsInt("maxBulkConcurrency",
                    Runtime.getRuntime().availableProcessors() * 2);
            map = toMap(request.param("map"));
            logger.info("index/type map = {}", map);
            URI destination = KnapsackHelper.getAddress(client, clusterName, request.param("host"), request.paramAsInt("port", 0), request.param("cluster"));
            String path = connection.getURI().getSchemeSpecificPart();
            final boolean copy = "copy".equals(request.param("mode"));
            final boolean s3 = "s3".equals(request.param("mode"));
            final KnapsackStatus status = new KnapsackStatus()
                    .setTimestamp(new Date())
                    .setMap(map)
                    .setPath(path)
                    .setURI(destination)
                    .setCopy(copy)
                    .setS3(s3);
            try {
                logger.info("starting import: {}", status);
                knapsackHelper.addImport(status);
                bulkClient.flushInterval(TimeValue.timeValueSeconds(5))
                    .maxActionsPerBulkRequest(maxActionsPerBulkRequest)
                    .maxConcurrentBulkRequests(maxBulkConcurrency)
                    .newClient(destination, KnapsackHelper.clientSettings(destination));
                logger.info("waiting for healthy cluster...");
                bulkClient.waitForCluster(ClusterHealthStatus.YELLOW, timeout);
                logger.info("... cluster is ready");
                packets = newLinkedHashMap();
                Packet<String> packet;
                String lastCoord = null;
                while ((packet = session.read()) != null) {
                    String[] entry = KnapsackPacket.decodeName(packet.name());
                    if (entry.length < 2) {
                        throw new ElasticsearchIllegalStateException("archive entry too short, can't import");
                    }
                    String index = entry[0];
                    String type = entry[1];
                    // entry length != 4 ? older knapsack format
                    String id = entry.length > 2 ? entry[2] : null;
                    String field = entry.length > 3 ? entry[3] : "_source";
                    if ("_settings".equals(type)) {
                        index = mapIndex(index);
                        String settings;
                        // override settings by user request
                        if (params.containsKey(index + "_settings")) {
                            InputStreamReader reader = new InputStreamReader(new FileInputStream(params.get(index + "_settings")), "UTF-8");
                            settings = Streams.copyToString(reader);
                            reader.close();
                        } else {
                            settings = packet.packet();
                        }
                        if (!"_all".equals(index)) {
                            logger.info("index {}: got settings {}", index, settings);
                            CreateIndexRequest createIndexRequest = indexRequestMap.get(index);
                            if (createIndexRequest == null) {
                                createIndexRequest = createIndexRequest(index);
                                indexRequestMap.put(index, createIndexRequest);
                            }
                            createIndexRequest.settings(settings);
                        }
                    }
                    else if ("_mapping".equals(id)) {
                        type = mapType(index, type);
                        index = mapIndex(index);
                        String mapping;
                        // override mappings by user request
                        if (params.containsKey(index + "_" + type + "_mapping")) {
                            InputStreamReader reader = new InputStreamReader(new FileInputStream(params.get(index + "_" + type + "_mapping")), "UTF-8");
                            mapping = Streams.copyToString(reader);
                            reader.close();
                        } else {
                            mapping = packet.packet();
                        }
                        if (!"_all".equals(index)) {
                            logger.info("index {}: got mapping {}", index, mapping);
                            CreateIndexRequest createIndexRequest = indexRequestMap.get(index);
                            if (createIndexRequest == null) {
                                createIndexRequest = createIndexRequest(index);
                                indexRequestMap.put(index, createIndexRequest);
                            }
                            createIndexRequest.mapping(type, mapping);
                        }
                    } else {
                        // normal document fields
                        String coord = index + File.separator + type + File.separator + id;
                        if (!coord.equals(lastCoord) && !packets.isEmpty()) {
                            indexPackets(packets);
                            packets.clear();
                        }
                        packets.put(field, packet);
                        lastCoord = coord;
                    }
                }
                if (!packets.isEmpty()) {
                    indexPackets(packets);
                }
                logger.info("end of import: {}", status);
                session.close();
                bulkClient.refresh().shutdown();
                bulkClient = null;
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            } finally {
                try {
                    knapsackHelper.removeImport(status);
                } catch (IOException ex) {
                    logger.error(ex.getMessage(), ex);
                }
            }
        }

        private void indexPackets(Map<String, Packet<String>> packets) throws IOException {
            String entryName = packets.values().iterator().next().name();
            String[] entry = KnapsackPacket.decodeName(entryName);
            if (entry.length < 3) {
                throw new ElasticsearchIllegalStateException("entry too short: " + entryName);
            }
            String index = entry[0];
            String type = entry[1];
            String id = entry[2];
            if (indexRequestMap.containsKey(index)) {
                CreateIndexRequest createIndexRequest = indexRequestMap.remove(index);
                if (request.paramAsBoolean("createIndex", true)) {
                    logger.info("creating index {}", index);
                    createIndexRequest.timeout(timeout);
                    try {
                        CreateIndexResponse response = bulkClient.client().admin().indices()
                                .create(createIndexRequest).actionGet();
                        if (!response.isAcknowledged()) {
                            logger.warn("index creation was not acknowledged");
                        }
                    } catch (IndexAlreadyExistsException e) {
                        if (request.paramAsBoolean("createIndex", true)) {
                            throw e;
                        } else {
                            logger.warn("index already exists: {}", index);
                        }
                    }
                }
            }
            IndexRequest indexRequest = new IndexRequest(mapIndex(index),
                    mapType(index, type), id);
            for (String f : packets.keySet()) {
                if (f == null) {
                    continue;
                }
                if ("_parent".equals(f)) {
                    indexRequest.parent(packets.get(f).packet());
                } else if ("_routing".equals(f)) {
                    indexRequest.routing(packets.get(f).packet());
                } else if ("_timestamp".equals(f)) {
                    indexRequest.timestamp(packets.get(f).packet());
                } else if ("_version".equals(f)) {
                    indexRequest.versionType(VersionType.EXTERNAL)
                            .version(Long.parseLong(packets.get(f).packet()));
                } else if ("_source".equals(f)) {
                    indexRequest.source(packets.get(f).packet());
                } else {
                    indexRequest.source(f, packets.get(f).packet());
                }
            }
            bulkClient.index(indexRequest);
        }

        private Map<String, Object> toMap(String s) {
            if (s == null) {
                logger.warn("no map defined");
                return newHashMap();
            }
            BytesArray ref = new BytesArray(s);
            try {
                byte[] b = ref.hasArray() ? ref.array() : ref.toBytes();
                XContentParser parser = XContentFactory.xContent(XContentType.JSON).createParser(b, 0, b.length);
                return parser.mapAndClose();
            } catch (IOException e) {
                logger.warn("parse error while reading map", e);
                return newHashMap();
            }
        }

        private String mapIndex(String index) {
            return map.containsKey(index) ? map.get(index).toString() : index;
        }

        private String mapType(String index, String type) {
            String s = index + "/" + type;
            return map.containsKey(s) ? map.get(s).toString() : type;
        }
    }

    private class RestKnapsackImportState implements RestHandler {

        @Override
        public void handleRequest(RestRequest request, RestChannel channel) {
            try {
                XContentBuilder builder = restContentBuilder(request);
                builder.startObject()
                        .field("imports")
                        .startArray();
                for (KnapsackStatus export : knapsackHelper.getImports()) {
                    export.toXContent(builder, EMPTY_PARAMS);
                }
                builder.endArray()
                        .endObject();
                channel.sendResponse(new XContentRestResponse(request, OK, builder));
            } catch (IOException ioe) {
                try {
                    channel.sendResponse(new XContentThrowableRestResponse(request, ioe));
                } catch (IOException e) {
                    logger.error("unable to send response to client");
                }
            }
        }
    }
}