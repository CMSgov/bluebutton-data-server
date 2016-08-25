Development Environment Setup
=============================

Thinking of contributing to this project? Great! This document provides some help on getting a development environment setup for that work.

## Common/Shared Instructions

Most of the setup and configuration for this project is the same as for the other Java-based Blue Button projects. Accordingly, please be sure to follow all of the instructions documented here, first: [bluebutton-parent-pom: Development Environment Setup](https://github.com/HHSIDEAlab/bluebutton-parent-pom/blob/devenv-instructions/dev/devenv-readme.md).

## Build Dependencies

This project depends on the [HAPI FHIR](https://github.com/jamesagnew/hapi-fhir) project. Releases of that project are available in the Maven Central repository, which generally makes things pretty simple: our Maven builds will pick up theirs.

Unfortunately, this project will sometimes need to depend on an interim/snapshot build of HAPI FHIR. When that's the case, developers will first need to locally checkout and `mvn install` that interim version themselves, manually. To keep this simpler, a fork of HAPI FHIR is maintained in the [HHSIDEAlab/hapi-fhir](https://github.com/HHSIDEAlab/hapi-fhir) repository on GitHub, which will always point to whatever version of HAPI FHIR this one depends on. You can checkout and build that fork, as follows:

    $ git clone https://github.com/HHSIDEAlab/hapi-fhir.git hhsidealab-hapi-fhir.git
    $ cd hhsidealab-hapi-fhir.git
    $ mvn clean install -DskipITs=true -DskipTests=true

Once the build is done, the HAPI FHIR artifacts will be placed into your user's local Maven repository (`~/.m2/repository`), available for use by this project or others.

## Wildfly

References:

* [WildFly 10 Admin Guide: Enable SSL](https://docs.jboss.org/author/display/WFLY10/Admin+Guide#AdminGuide-EnableSSL)
* [Java EE 5 Tutorial: Creating a Client Certificate for Mutual Authentication](https://docs.oracle.com/cd/E19575-01/819-3669/bnbyi/index.html)

In the HealthAPT dev, test, and prod environments in AWS, this application is deployed to a WildFly 10 container. It's a good idea to ensure that your local dev environment mirrors that, though you _can_ use any container you'd like.

It's worth noting that this project's integration tests actually use Jetty, as that's easy to run embedded from the test classpath. (The main reason, though, is that this is just what the upstream HAPI FHIR sample project uses, and we haven't changed it.)

It really shouldn't be necessary for local development, but here are the instructions on how to install and configure WildFly 10 in a way that mostly mirrors the project's production environment:

1. Download the latest WildFly 10 release: <http://wildfly.org/downloads/>.
1. Extract it to a local directory, e.g.:

    ```
    $ tar --extract --directory ~/workspaces/tools/ --file ~/Downloads/wildfly-10.1.0.Final.tar.gz
    ```

1. Start WildFly by running the following command in a separate terminal that you leave open (adjust the path to match your system):
    
    ```
    $ ~/workspaces/tools/wildfly-10.1.0.Final/bin/standalone.sh
    ```
    
1. The first time you launch WildFly, if you try to go to the WildFly admin console at <http://localhost:9990/>, you will receive an error instructing you on how to create a local admin user. Follow those instructions, as they're required.
1. Configure mandatory mutual (server & client) SSL authentication:
    1. For your convenience, a dev-only-really-don't-use-these-anywhere-else server keystore and client truststore (with certs) have been generated and saved in this project's `dev` directory. Originally, these were generated as follows:
        1. Generate a new server keypair that's valid for `localhost` and `127.0.0.1` and a new keystore for it using Java's `keytool` in the WildFly server's `standalone/configuration` directory, e.g.:
            
            ```
            $ keytool -genkeypair -alias server -keyalg RSA -keysize 4096 -dname "cn=localhost" -ext "san=ip:127.0.0.1" -validity 3650 -keypass changeit -keystore bbonfhir-server.git/dev/ssl-stores/server.keystore -storepass changeit
            $ cp bbonfhir-server.git/dev/ssl-stores/server.keystore ~/workspaces/tools/wildfly-10.1.0.Final/standalone/configuration/
            ```
            
        1. Generate a new client certificate that can be used in tests and place it in a new server truststore:
            
            ```
            $ keytool -genkeypair -alias client-local-dev -keyalg RSA -keysize 4096 -dname "cn=client-local-dev" -validity 3650 -keypass changeit -keystore bbonfhir-server.git/dev/ssl-stores/client.keystore -storepass changeit
            $ keytool -exportcert -alias client-local-dev -file bbonfhir-server.git/dev/ssl-stores/client.cer -keystore bbonfhir-server.git/dev/ssl-stores/client.keystore -storepass changeit
            $ keytool -importcert -noprompt -trustcacerts -alias client-local-dev -file bbonfhir-server.git/dev/ssl-stores/client.cer -keypass changeit -keystore bbonfhir-server.git/dev/ssl-stores/server.truststore -storepass changeit
            $ cp bbonfhir-server.git/dev/ssl-stores/server.truststore ~/workspaces/tools/wildfly-10.1.0.Final/standalone/configuration/
            ```
            
    1. Export the client certificate to a PFX file that you can use in your browser, if need be:
        
        ```
        $ keytool -importkeystore -srckeystore bbonfhir-server.git/dev/ssl-stores/client.keystore -destkeystore bbonfhir-server.git/dev/ssl-stores/client.pfx -deststoretype PKCS12 -srcstorepass changeit -deststorepass changeit -srcalias client-local-dev
        ```
            
    1. Edit the WildFly configuration using the CLI to configure the server SSL and to require client SSL, as follows:
    
        ```
        $ ~/workspaces/tools/wildfly-10.1.0.Final/bin/jboss-cli.sh
        [disconnected /] connect
        [standalone@localhost:9990 /] /core-service=management/security-realm=ApplicationRealm/server-identity=ssl/:write-attribute(name=keystore-path,value="server.keystore")
        [standalone@localhost:9990 /] /core-service=management/security-realm=ApplicationRealm/server-identity=ssl/:write-attribute(name=keystore-password,value=changeit)
        [standalone@localhost:9990 /] /core-service=management/security-realm=ApplicationRealm/server-identity=ssl/:write-attribute(name=key-password,value=changeit)
        [standalone@localhost:9990 /] /core-service=management/security-realm=ApplicationRealm/authentication=truststore:add(keystore-path="server.truststore",keystore-password=changeit)
        [standalone@localhost:9990 /] /subsystem=undertow/server=default-server/https-listener=https/:write-attribute(name=verify-client,value=REQUIRED)
        ```
        
1. Configure the system properties required by the Blue Button FHIR server, as follows:
    
    ```
    $ ~/workspaces/tools/wildfly-10.1.0.Final/bin/jboss-cli.sh
    [disconnected /] connect
    [standalone@localhost:9990 /] /system-property=bbfhir.db.url:add(value="jdbc:hsqldb:mem:test")
    [standalone@localhost:9990 /] /system-property=bbfhir.db.username:add(value="")
    [standalone@localhost:9990 /] /system-property=bbfhir.db.password:add(value="")
    ```
    
1. Reload WildFly to apply all of the configuration changes and then deploy the HAPI FHIR server WAR, as follows (adjust the paths to match your system):
    
    ```
    $ ~/workspaces/tools/wildfly-10.1.0.Final/bin/jboss-cli.sh
    [disconnected /] connect
    [standalone@localhost:9990 /] :reload
    [standalone@localhost:9990 /] deploy ~/workspaces/cms/bbonfhir-server.git/bbonfhir-server-app/target/bbonfhir-server-app-0.1.0-SNAPSHOT.war --name=bbonfhir-server.war
    [standalone@localhost:9990 /] quit
    ```
    
At this point, things should be running and available. Note that, now that the client SSL configuration has "`verify-client`" set to "`REQUIRED`", you will be unable to access HAPI's testing UI in your web browser unless you first deploy the `client.pfx` file to your browser (temporarily!). All API access will also require a trusted client certificate, as well. If you just want to poke around in the Testing UI, you can temporarily adjust "`verify-client`" "`REQUESTED`" and `:reload` WildFly. Note, though, that this **must not** be done in production, as it completely disables the application's authentication requirements.
