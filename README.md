# Building Reactive Microservice Systems

This project is based mainly on the references below.

        <http://escoffier.me/vertx-kubernetes/>

        <https://vertx.io/docs/vertx-service-discovery/java/>

        <https://github.com/cescoffier/vertx-microservices-workshop>

        <https://github.com/cescoffier/vertx-kubernetes-workshop>

        <https://severalnines.com/database-blog/using-kubernetes-deploy-postgresql>

        <https://github.com/yunyu/vertx-console>
        
        ESCOFFIER, C. Building Reactive Microservices in Java Asynchronous and Event-Based Application Design. First Edition. California: O’Reilly Media, Inc., 2017.

        RedHat Developer, accessed 1 November 2019, <https://developers.redhat.com/promotions/building-reactive-microservices-in-java>

        Kubernetes Hands-On - Deploy Microservices to the AWS Cloud 2018, Udemy, accessed 1 November 2019, <https://www.udemy.com/course/kubernetes-microservices>

        <https://github.com/hazelcast/hazelcast-code-samples/>

        <https://vertx.io/docs/vertx-hazelcast>


## Audit service

The audit service receives operations (shares bought or sold) from the event bus and store them in a database. It also provides a REST endpoint to retrieve the last 10 operations.

## The Audit service

The law is the law... The Sarbanes - Oxley Act requires you to keep a track of every transaction you do on a financial market.

The audit service records the shares you buy and sell in a dateabase. It's going to be a PostGreSQL database, but would be similar with another database, even no-sql database. The database is going to be deployed in Kubernetes.

In this project we are going to cover:

    * Advanced asynchronous orchestration

    * Asynchronous JDBC

    * Vert.x Web to build REST API

    * Managing Secrets with Kubernetes


### Accessing data asynchronously

As said previously, Vert.x is asynchronous and you must never block the event loop. And you know what'd definitely blocking? Databases accesses and more particularly JDBC. 

Fortunately, Vert.x provides a JDBC client that is asynchronous.

The principle is simple and is applied to all clients accessing blocking systems:

    1. The application send the query, instruction, statement to the client; with a Handler

    2. The client enqueue in a Worker the task to be done

    3. The client returns immediately

    4. The Worker executes the task on the database

    5. When done, the Worker notifies the client

    6. The client invokes the handler (in the same thread as the interaction (1) with the operation result)


    * Worker? Yes, Vert.x has the notion of Workers (a separate thread pool) to execute blocking code. It can be a verticle marked as Worker or with the vertx.executeBlocking construct. However, even if possible, you should not abuse from these features as it reduces the scalability of the application.


However, interactions with databases are rarely a single application, but a composition of operations. For example:

    1. Get a connection

    2. Drop some tables

    3. Create some tables

    4. Close the connection

So, we need a way to compose these operations, and report failures when required. This is what we are going to see in the Audit service.


### The Audit service

The Audit service:

    1. Listens for the financial operations on the event bus

    2. Stores the received operations in the database

    3. Exposes a REST API to get the last 10 operations

Interactions with the database use the vertx-jdbc-client, an async version of JDBC. So expect to see some SQL code (I know you love it). But, to orchestrate all these asynchronous calls, we need the right weapons. We are going to use RX Java 2 for this.

### Task - Composing methods returning Single

Open the AuditVerticle class.

The first important detail of this verticle is its start method. As the start method from the traders, the method is asynchronous, and report its completion in the given Future object:


        @Override
        public void start(Future<Void> future) {

            Single<JDBCClient> jdbc = JDBCDataSource.rxGetJDBCClient(discovery,
                service -> service.getName().equals("audit-database"), getDatabaseConfiguration()    
            );

            // TODO
            // ---
            Single<MessageConsumer<JsonObject>> readySingle = Single.error(new UnsupportedOperationException("not yet implemented"));
            // ---

            readySingle.doOnSuccess(consumer -> {
                // on success we set the handler that will store message in the database
                consumer.handler(message -> storeInDatabase(message.body()));
            })
            .subscribe(
                consumer -> {
                    // complete the verticle start with a success
                    future.complete();
                    ready = true;
                },
                error -> {
                    // signal a verticle start failure
                    future.fail(error);
                }
            );
        }

Vert.x would consider the verticle deploy when the Future is valuated. It may also report a failure if the verticle cannot be started correctly.

Initializing the audit service includes:

    * Discover and configure the database (already in the code), and prepare the database (create the table)

    * Start the Http service and expose the REST API

    * Retrieve the message source on which the operation is sent


So clearly 3 independent actions, but the audit service is started only when all of them have been completed. So, we need to implement this orchestration.

This code should retrieve 3 Single objects (from methods provided in the class) and wait for the completion of the three tasks. The three singles should be combined in one Single<MessageConsumer<JsonObject>>. Don't forget that the initializeDatabase requires the JDBC client as parameter and so should be called once the jdbc Single has completed. Also look at the retrieveThePortfolioMessageSource method to see how you can create a Single object from an already known entity (we should have used service discovery - it's just to give you an example).

When you have the three Singles, zip them to be notified when all of them have completed. The zip function must return the MessageConsumer<JsonObjetc>.

On success this Single registers a message listener on the portfolio message source storing the operation in the database for each received message.

Its completion notifies Vert.x that the start process is completed (or successfully or not), it calls future.complete() and future.fail(cause).

            Single<JDBCClient> databaseReady = jdbc.flatMap(client -> initializeDatabase(client, true));
            Single<HttpServer> httpServerReady = configureTheHttpServer();
            Single<MessageConsumer<JsonObject>> messageConsumerReady = retrieveThePortfolioMessageSource();

            Single<MessageConsumer<JsonObject>> readySingle = Single.zip(
                databaseReady, httpServerReady, messageConsumerReady,
                (db, http, consumer) -> consumer
            );

First we create 3 Singles, one per action to execute. We are going to see how they are created in a minute. Then we compose all of them using the Single.zip operator. The zip function returns the consumer single as this is the one we really care about.

### Publishing Services

Once you have a service discovery instance, you can publish services. The process is the following:

    1. Create a record for a specific service provider

    2. Publish the record

    3. Keep the published record that is used to up-publish a service or modify it

To create records, you can either use the Record class, or use convenient methods from the service types.

        // Record creation from a type
        Record record = HttpEndpoint.createRecord("some-rest-api", "localhost", 8080, "/api");
        discovery.publish(record, ar -> {
            if (ar.succeeded()) {
                // publication succeeded
                Record publishedRecord = ar.result();
            } else {
                // publication failed
            }
        });

It is important to keep a reference on the returned records, as this record has been extended by a registration id.

### Types of Services

As said above, the service discovey has the service type concept to manage the heterogeneity of the different services.

These services are provided by default:

    * HttpEndpoint - for REST API's, the service object is a HttpClient configured on the host and port (the location is the url)

    * EventBusService - for service proxies, the service object is a proxy. Its type is the proxy interface (the location is the address)

    * MessageSource - for message sources (publisher), the service object is a MessageConsumer (the location is the address)

    * JDBCDataSource - for JDBC data sources, the service object is a JDBCClient (the configuration of the client is computed from the location, metadata and consumer configuration)

### HTTP endpoints

A Http endpoint represents a REST API or a service accessible using Http requests. The Http endpoint service objects are HttpClient configured with the host, port and ssl.

### Publishing a HTTP endpoint

To publish a Http endpoint, you need a record. You create a record using HttpEndpoint.createRecord.

        Record record = HttpEndpoint.createRecord(
            "some-http-service",        // The service name
            "localhost",                // The host
            "8433",                     // the port
            "/api"                      // the root of the service
        );

        discovery.publish(record, ar -> {
            // ...
        });

When you run your service in a container or on the cloud, it may not know its public IP and public port, so the publication must be done by another entity having this info. Generally it's a bridge. In our project, we may publish the Audit Service by defining the bridge as an Ingress Controller which routes to the host named as audit-service-reactive-microservices.sidartasilva.io:

            Record record = HttpEndpoint.createRecord("audit", 
                    "audit-service-reactive-microservices.sidartasilva.io");
            discovery.publish(record, ar -> {
                if (ar.succeeded()) {
                // publication succeeded
                System.out.println("\n\n\nPublication of the audit service succeeded.");
                } else {
                // publication failed
                System.err.println("\n\n\nPublication of the audit service failed.");
                }
            });


### Audit Service Ingress Controller

            apiVersion: extensions/v1beta1
            kind: Ingress
            metadata:
            name: public-routing-audit-service
            spec:
            rules:
                - host: audit-service-reactive-microservices.sidartasilva.io
                http:
                    paths:
                    - path: /
                        backend:
                        serviceName: audit-service
                        servicePort: 8089


### Consuming a HTTP endpoint

Once a HTTP endpoint is published, a consumer can retrieve it. The service object is a HttpClient with a port and host configured.

            discovery.getRecord(new JsonObject().put("name", "some-http-service"), ar -> {
                if (ar.succeeded() && ar.result() != null) {
                    // Retrieve the service reference
                    ServiceReference reference = discovery.getReference(ar.result());
                    // Retrieve the service object
                    HttpClient client = reference.getAs(HttpClient.class);

                    // You need to path the complete path
                    client.getNow("/api/persons", response -> {

                        // ...

                        // Don't forget to release the reference
                        reference.release();

                    });
                }
            });


You can also use the HttpEndpoint.getClient method to combine lookup and service retrieval in one call:

            HttpEndpoint.getClient(discovery, new JsonObject().put("name", "some-http-service"), ar -> {
                if (ar.succeeded()) {

                    HttpClient client = ar.result();

                    // You need to path the complete path
                    client.getNow("/api/persons", response -> {
                        

                        // ...

                        // Don't forget to release the service
                        ServiceDiscovery.releaseServiceObject(discovery, client);

                    });

                }
            });

In this second version, the service object is released using ServiceDiscovery.releaseServiceObject, so you don't need to keep the service reference.

Since Vert.x 3.4.0, another client has been provided. The higher-level client, named WebClient tends to be easier to use. You can retrieve a WebClient instance using:

        discovery.getRecord(new JsonObject().put("name", "some-http-service"), ar -> {
            if (ar.succeeded() && ar.result() != null) {
                // Retrieve the service reference
                ServiceReference reference = discovery.getReference(ar.result());

                // Retrieve the service object
                WebClient client = reference.getAs(WebClient.class);

                // You need to path the complete path
                client.get("/api/persons").send(
                    response -> {

                        // ...

                        // Don't forget to release the service
                        reference.release();

                });
            }
        });

And, if you prefer the approach using the service type: 


            HttpEndpoint.getWebClient(discovery, new JsonObject("name", "some-http-service"), ar -> {
                if (ar.succeeded()) {

                    WebClient client = ar.result();

                    // You need to path the complete path
                    client.get("/api/persons)
                        .send(response -> {


                            // ...

                            // Don't forget to release the service
                            ServiceDiscovery.releaseServiceObject(discovery, client);

                        });

                }
            });


In our project, in the DashboardVerticle class, we have the retrieveAuditVerticle method, where we can consume the Audit Service by filtering by the service name which was published earlier by the AuditVerticle class:


            private Future<WebClient> retrieveAuditService() {
                Future<WebClient> future = Future.future();
                HttpEndpoint.getWebClient(discovery, new JsonObject().put("name", "audit"), future);
                return future;
            }




### Task - Implementing a method returning a Single & Vert.x Web

Ok, but some of the methods we used in the previous section are not totally functional. Let's fix this. Look at the configureTheHttpServer method. In this method we are going to use a new Vert.x Component: Vert.x Web.

Vert.x Web is a Vert.x extension to build modern web applications. Here we are going to use a Router which let us implement REST APIs easily. So:

    1. Create a Router object with: Router.router(vertx)

    2. Register a route (/) on the router, calling retrieveOperations (using rouger.get("/).handler(...))

    3. Create a Http server delegating the request handler to router.accept

    4. Retrieve the port passed in the configuration or 0 if not set (if picks an available port), we can pick a random port as it is exposed in the service record, so consumers are bound to the right port

    5. Start the server with the rxListen verion of the listen method that returns a single.


            private Single<HttpServer> configureTheHttpServer() {
                Router router = Router.router(vertx);

                router.get("/")
                    .handler(this::retrieveOperations);

                return vertx.createHttpServer()
                    .requestHandler(router)
                    .rxListen(8089);

            }


It creates a Router. The Router is an object from Vert.x Web that ease the creation of REST API with Vert.x. We won't go into too much details here, but if you want to implement REST API with Vert.x, this is the way to go. 

On our Router we declare a route: when a request arrive on /, it calls this Handler. Then, we create the Http server. The requestHandler is a specific method of the router, and we return the result of the rxListen method.

So the caller can call this method and get a Single. It can subscribe on it to bind the server and be notified of the completion of the operation (or failure).


## Using Async JDBC

In the start method , we are calling initializeDatabase. This method is also not very functional at this point. Let's look at this method using another type of action composition. This method:

    * get a connection to the database

    * drop the table

    * create the table

    * close the connection (whatever the result of the two last operations)

All these operations may fail. Unlike in the start method where the actions were unrelated, these actions are related. Fortunately, we can chain asynchronous actions using the flatMap operator of RX Java 2.

            Single<X> chain = input.flatMap(function1);


So to use the composition pattern, we just need a set of Functions and a Single that would trigger the chain.

Let's start slowly.

In the TODO block, write the following snippet to create the Single and trigger the chain:

            // This is the starting point of our operations
            // This Single will be completed when the connection with the database is established.
            // We are going to use this Single as a reference on the connection to close it.
            Single<SQLConnection> connectionRetrieved = jdbc.rxGetConnection();


Then, we need to compose the Single with the flatMap operator that is taking a SQLConnection as parameter and returns a Single containing the result of the database initialization:

        1. We create the batch to execute

        2. The rxBatch executes. The batch gives us the Single returns of the operation

        3. Finally we close the connection with doAfterTerminate()

So, write:

            return connectionRetrieved
                    .flatMap(conn -> {
                        // When the connection is retrieved

                        // Prepare the batch
                        List<String> batch = new ArrayList<>();

                        if (drop) {
                            // When the table is dropped we recreate it
                            batch.add(DROP_STATEMENT);
                        }
                        // Just create the table
                        batch.add(CREATE_TABLE_STATEMENT);

                        // We compose with a statement batch
                        Single<List<Integer>> next  = conn.rxBatch(batch);

                        // Whatever the result, if the connection has been retrieved, close it
                        return next.doAfterTerminate(conn::close);
                    })
                    .map(list -> client);



### Task - Expose readiness

The audit service needs to orchestrate a set of tasks before being ready to serve. 

We should indicate this readiness state to Kubernetes so it can know when we are ready. This would let it implement a rolling update strategy without downtime as the previous version of the service will be used until the new one is ready.

You may have noticed that our class has a ready field set to true when we have completed our startup. In addition, our pom.xml has the <vertx.health.path>/health</vertx.health.path> property indicating a health check. It instructs Kubernetes to ping this endpoint to know when the application is ready. But, there is still one thing required: serving these requests. Jump back to the configureTheHttpServer method and add a route handling GET /health and returning a 200 response when the ready field is true, or a 503 response otherwise. Set the status code with: rc.response().setStatusCode(200).end("Ready") and don't forget to call end().


                    Router router = Router.router(vertx);
                    router.get("/")
                        .handler(this::retrieveOperations);
                    router.get("/health")
                        .handler(rc -> {
                            if (ready) {
                                rc.response().end("Ready");
                            } else {
                                // Service not yet available
                                rc.response().setStatusCode(503).end();
                            }
                        });

                    return vertx.createHttpServer().requestHandler(router)
                        .rxListen(8089);

With this in place, during the deployment, you will see that the Pod state stays a "long" time in the not ready state. When the readiness check succeed, Kubernetes starts routing request to this Pod.

### Task - Async JDBC with a callback-based composition

You may ask why we do such kind of composition. Let's implement a method without any composition operator (just using callbacks).

The retrieveOperations method is called when an Http request arrives and should return a Json object containing the last 10 operations. So in other words:

    1. Get a connection to the database

    2. Query the database

    3. Iterate over the result to get the list

    4. Write the list in the Http response

    5. Close the database

The step 1 and 2 are asychronous. 5 is asynchronous too, but we don't have to wait for the completion. In this code, don't use composition (that's the purpose of this exercise). In retrieveOperations, write the required code using Handlers / Callbacks.

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
                            // Write this list into the response
                            context.response().setStatusCode(200).end(Json.encodePrettily(operations));
                            connection.close();
                        }
                    });
                }
            });

So obviously it is possible not to use RX Java as well. But imagine when you have several asynchronous operations to chain, it becomes a callback hell very quickly. But again, Vert.x gives you the freedom to choose what you prefer.


### Show time!

Let's see how this works. But wait... we need a database!

Let's deploy in Kubernetes using:

            apiVersion: apps/v1
            kind: Deployment
            metadata:
            name: postgres
            spec:
            replicas: 1
            selector:
                matchLabels:
                app: postgres
            template:
                metadata:
                labels:
                    app: postgres
                spec:
                containers:
                    - name: postgres
                    image: postgres:12.1-alpine
                    imagePullPolicy: "IfNotPresent"
                    ports:
                        - containerPort: 5432
                    envFrom:
                        - configMapRef:
                            name: postgres-config
                    volumeMounts:
                        - mountPath: /var/lib/postgresql/data
                        name: postgredb
                volumes:
                    - name: postgredb
                    persistentVolumeClaim:
                        claimName: postgres-pv-claim

            ---
            apiVersion: v1
            kind: Service
            metadata:
            name: audit-database
            labels:
                app: postgres
            spec:
            type: NodePort
            ports:
            - port: 5432
            selector:
            app: postgres


It creates a new database service named audit-database with the given credentials and settings. Be aware that this database is using Persistence Storage Volume:

            kind: PersistentVolume
            apiVersion: v1
            metadata:
            name: postgres-pv-volume
            labels:
                type: local
                app: postgres
            spec:
            storageClassName: manual
            capacity:
                storage: 100Mi
            accessModes:
                - ReadWriteMany
            hostPath:
                path: "/mnt/data"
            ---
            kind: PersistentVolumeClaim
            apiVersion: v1
            metadata:
            name: postgres-pv-claim
            labels:
                app: postgres
            spec:
            storageClassName: manual
            accessModes:
                - ReadWriteMany
            resources:
                requests:
                storage: 100Mi


Now, we can deploy our audit service.

Refresh the dashboard, and you should see the operations in the top right corner!

![](images/Captura-de-tela-de-2020-01-01-11-27-22.png "The Audit Service - Last Operations")


### Managing Secrets

But wait... we have hardcoded the database credentials in our code. This is not optimal. Kubernetes provides a way to manage secrets.

Let's first create a Secret entity using:

            apiVersion: "v1"
            kind: "Secret"
            metadata:
            name: "audit-database-config"
            stringData:
            user: "admin"
            password: "secret"
            url: "jdbc:postgresql://audit-database:5432/audit"

There are several ways to access secrets from your application:

    * ENV variables

    * Monted as a file

    * Using the Vert.x config

For sake of simplicity we are going to use the first approach.

So we first need to bind the secret with our deployment.

            apiVersion: apps/v1
            kind: Deployment
            metadata:
            name: audit-service
            spec:
            replicas: 1
            selector:
                matchLabels:
                app: audit-service
            template:
                metadata:
                labels:
                    app: audit-service
                spec:
                containers:
                - name: audit-service
                    image: sidartasilva/audit-service:latest
                    env:
                    - name: DB_USERNAME
                        valueFrom:
                        secretKeyRef:
                            name: audit-database-config
                            key: user
                    - name: DB_PASSWORD
                        valueFrom:
                        secretKeyRef:
                            name: audit-database-config
                            key: password
                    - name: DB_URL
                        valueFrom:
                        secretKeyRef:
                            name: audit-database-config
                            key: url
                    imagePullPolicy: Always
                    ports:
                    - containerPort: 5701
                    - containerPort: 8089

            ---
            apiVersion: v1
            kind: Service
            metadata:
            name: audit-service
            spec:
            type: NodePort
            selector:
                app: audit-service
            ports:
            - name: hazelcast
                port: 5701
            - name: app
                port: 8089


Notice the 3 last env variables retrieving values from the audit-database-config secret.

Now, we need to update our code. Open the AuditVerticle class and replace the content of the getDatabaseConfiguration method with:

            private JsonObject getDatabaseConfiguration() {
                return new JsonObject()
                    .put("user", System.getenv("DB_USERNAME"))
                    .put("password", System.getenv("DB_PASSWORD"))
                    .put("driver_class", "org.postgresql.Driver")
                    .put("url", System.getenv("DB_URL"));
            }


And we should redeploy our Audit Service application.

