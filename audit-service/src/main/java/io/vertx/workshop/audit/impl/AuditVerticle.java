package io.vertx.workshop.audit.impl;

import io.reactivex.Single;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.ext.sql.UpdateResult;
import io.vertx.reactivex.core.AbstractVerticle;
import io.vertx.reactivex.core.eventbus.MessageConsumer;
import io.vertx.reactivex.core.http.HttpServer;
import io.vertx.reactivex.ext.jdbc.JDBCClient;
import io.vertx.reactivex.ext.sql.SQLConnection;
import io.vertx.reactivex.ext.web.Router;
import io.vertx.reactivex.ext.web.RoutingContext;
import io.vertx.reactivex.servicediscovery.ServiceDiscovery;
import io.vertx.reactivex.servicediscovery.types.JDBCDataSource;
import io.vertx.servicediscovery.Record;
import io.vertx.servicediscovery.types.HttpEndpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AuditVerticle extends AbstractVerticle {

    private static final String DROP_STATEMENT = "DROP TABLE IF EXISTS AUDIT";
    private static final String CREATE_TABLE_STATEMENT = "CREATE TABLE IF NOT EXISTS AUDIT (id SERIAL PRIMARY KEY, operation varchar(250) NOT NULL)";
    private static final String INSERT_STATEMENT = "INSERT INTO AUDIT (operation) VALUES (?)";
    private static final String SELECT_STATEMENT = "SELECT * FROM AUDIT ORDER BY ID DESC LIMIT 10";

    private JDBCClient jdbc;
    private boolean ready;  

    @Override
    public void start(Future<Void> future) {
        ServiceDiscovery.create(vertx, discovery -> {

            Single<JDBCClient> jdbc = JDBCDataSource.rxGetJDBCClient(discovery,
                svc -> svc.getName().equals("audit-database"),
                getDatabaseConfiguration()
            ).doOnSuccess(jdbcClient -> this.jdbc = jdbcClient);

            Single<JDBCClient> databaseReady = jdbc
                .flatMap(client -> initializeDatabase(client, true));

            Record record = HttpEndpoint.createRecord("audit", 
                    "audit-service-reactive-microservices.sidartasilva.io");
            discovery.publish(record, ar -> {
                if (ar.succeeded()) {
                // publication succeeded
                System.out.println("\n\n\nPublication of service audit succeeded.");
                } else {
                // publication failed
                System.err.println("\n\n\nPublication of service audit failed.");
                }
            });

            Single<HttpServer> httpServerReady = configureTheHTTPServer();
            Single<MessageConsumer<JsonObject>> messageConsumerReady = retrieveThePortfolioMessageSource();

            Single<MessageConsumer<JsonObject>> readySingle = Single.zip(databaseReady, httpServerReady,
                messageConsumerReady, (db, http, consumer) -> consumer);

            readySingle.doOnSuccess(consumer -> {
                consumer.handler(message -> storeInDatabase(message.body()));
            }).subscribe(consumer -> {
                future.complete();
                ready = true;
            }, future::fail);
        });


    }

    private JsonObject getDatabaseConfiguration() {
      return new JsonObject()
        .put("user", System.getenv("DB_USERNAME"))
        .put("password", System.getenv("DB_PASSWORD"))
        .put("driver_class", "org.postgresql.Driver")
        .put("url", System.getenv("DB_URL"));
    }

    @Override
    public void stop(Future<Void> future) throws Exception {
        jdbc.close();
        super.stop(future);
    }

    private void retrieveOperations(RoutingContext context) {
        jdbc.getConnection(ar -> {
            SQLConnection connection = ar.result();
            if (ar.failed()) {
                context.fail(ar.cause());
            } else {
                connection.query(SELECT_STATEMENT, result -> {
                    if (result.failed()) {
                        context.fail(result.cause());
                    } else {
                        ResultSet set = result.result();
                        List<JsonObject> operations = set.getRows().stream()
                            .map(json -> new JsonObject(json.getString("operation")))
                            .collect(Collectors.toList());
                        context.response().setStatusCode(200).end(Json.encodePrettily(operations));
                        connection.close();
                    }
                });
            }
        });
    }

    private Single<HttpServer> configureTheHTTPServer() {
        Router router = Router.router(vertx);
        router.get("/").handler(this::retrieveOperations);
        router.get("/health").handler(rc -> {
            if (ready) {
                rc.response().end("Ready");
            } else {
                rc.response().setStatusCode(503).end();
            }
        });
        return vertx.createHttpServer().requestHandler(router)
            .rxListen(8089);
    }

    private Single<MessageConsumer<JsonObject>> retrieveThePortfolioMessageSource() {
        return Single.just(vertx.eventBus().consumer("portfolio"));
    }


    private void storeInDatabase(JsonObject operation) {
        Single<SQLConnection> connectionRetrieved = jdbc.rxGetConnection();

        Single<UpdateResult> update = connectionRetrieved
            .flatMap(connection ->
                connection.rxUpdateWithParams(INSERT_STATEMENT, new JsonArray().add(operation.encode()))
                    .doAfterTerminate(connection::close));
        update.subscribe(result -> {
            // Ok
        }, err -> {
            System.err.println("Failed to insert operation in database: " + err);
        });
    }

    private Single<JDBCClient> initializeDatabase(JDBCClient client, boolean drop) {
        Single<SQLConnection> connectionRetrieved = client.rxGetConnection();
        return connectionRetrieved
            .flatMap(conn -> {
                List<String> batch = new ArrayList<>();
                if (drop) {
                    batch.add(DROP_STATEMENT);
                }
                batch.add(CREATE_TABLE_STATEMENT);
                Single<List<Integer>> next = conn.rxBatch(batch);
                return next.doAfterTerminate(conn::close);
            })
            .map(list -> client);
    }
}