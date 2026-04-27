# Third-Party Dependencies

This file lists out all of the dependencies the project uses that are listed in the pom.xml

## Runtime and API dependencies

| Dependency | Purpose in this project | Where it is used | Notes |
| --- | --- | --- | --- |
| `build.buf.gen:meshtastic_protobufs_protocolbuffers_java` | Generated Java classes for the Meshtastic protobuf schema | Client protocol model, admin/config payloads, event decoding, request building | Distributed through Buf Schema Registry; this dependency is the main reason the project license was aligned to GPL-3.0 |
| `com.google.protobuf:protobuf-java` | Protobuf runtime required by the generated Meshtastic classes | Everywhere protobuf messages are parsed or built | Runtime support library for generated protobuf types |
| `org.slf4j:slf4j-api` | Logging facade | Core client, transports, handlers, services, and examples | The library logs through SLF4J rather than binding to a single concrete backend |
| `com.fazecast:jSerialComm` | Serial/USB transport support | `SerialTransport`, `SerialConfig` | Used only for serial connections, not for TCP |

## Compile-time dependency

| Dependency | Purpose in this project | Where it is used | Notes |
| --- | --- | --- | --- |
| `org.projectlombok:lombok` | Reduces boilerplate for immutable values, builders, and loggers | Model classes, events, services, handlers | Compile-time only; not part of the runtime API surface |

## Test-only dependencies

| Dependency | Purpose in this project | Where it is used | Notes |
| --- | --- | --- | --- |
| `org.junit.jupiter:junit-jupiter` | Unit and integration test framework | `src/test/java` | Main automated test framework |
| `org.slf4j:slf4j-simple` | Lightweight logging backend for tests and local example runs | Test runtime and JBang examples | Used so examples and tests can show logs without extra setup |

## Documentation and build tooling

These are also third-party dependencies, but they are primarily part of the build and documentation toolchain rather
than the shipped runtime artifact:

- `org.asciidoctor:asciidoctor-maven-plugin`
- `org.asciidoctor:asciidoctorj-diagram`
- `org.asciidoctor:asciidoctorj-diagram-plantuml`
- `com.diffplug.spotless:spotless-maven-plugin`
- `com.palantir.javaformat:palantir-java-format`
- standard Apache Maven plugins used for compile, test, source, javadoc, and deploy workflows

## Transport-specific note

`jSerialComm` is used only for serial/USB connections.

TCP transport support does **not** use `jSerialComm`; it is implemented with standard JDK networking APIs in:

- `/Users/tmulle/NetBeansProjects/meshtastic-java-client/src/main/java/com/meshmkt/meshtastic/client/transport/stream/tcp/TcpTransport.java`

Serial transport support is implemented in:

- `/Users/tmulle/NetBeansProjects/meshtastic-java-client/src/main/java/com/meshmkt/meshtastic/client/transport/stream/serial/SerialTransport.java`
- `/Users/tmulle/NetBeansProjects/meshtastic-java-client/src/main/java/com/meshmkt/meshtastic/client/transport/stream/serial/SerialConfig.java`
