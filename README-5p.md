# MCP Java SDK
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/license/MIT)
[![Build Status](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/publish-snapshot.yml/badge.svg)](https://github.com/modelcontextprotocol/java-sdk/actions/workflows/publish-snapshot.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.modelcontextprotocol.sdk/mcp.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.modelcontextprotocol.sdk/mcp)
[![Java Version](https://img.shields.io/badge/Java-17%2B-orange)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)

Interne Hinweise zur Entwicklung und zum Deployment des MCP Java SDK.

## Lokale Entwicklung

Bei der lokalen Entwicklung muss die Version in der `pom.xml` mit dem Suffix `-SNAPSHOT` versehen werden, damit Artefakte als Snapshot-Versionen in das lokale Maven-Repository installiert werden können. Das aktuelle Versionsschema folgt dem Muster `<Hauptversion>-5p-<Laufnummer>`, z. B. `2.0-5p-0`.

> Hinweis: Die Versionsänderung kann bequem per „Suchen und Ersetzen" über alle `pom.xml`-Dateien des Projekts hinweg durchgeführt werden (z. B. `2.0-5p-0-SNAPSHOT` → `2.0-5p-0`).

Beispiel für eine lokale Entwicklungsversion:

```xml
<version>2.0-5p-0-SNAPSHOT</version>
```

Zum Bauen und Installieren der Artefakte in das lokale Maven-Repository wird folgender Befehl verwendet:

```bash
mvn install
```

Dadurch stehen die gebauten Module anschließend in anderen lokalen Projekten als Abhängigkeiten zur Verfügung, ohne dass ein Release erforderlich ist.

### Lokale Snapshots in anderen Projekten verwenden

Wurde das SDK lokal mit `mvn install` gebaut, landen die Artefakte im lokalen Maven-Repository unter `~/.m2/repository`. Dadurch können sie ohne weitere Konfiguration aus einem anderen Maven-Projekt als Abhängigkeit eingebunden werden.

Beispiel für einen Eintrag in der `pom.xml` eines anderen Projekts:

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
    <version>2.0-5p-0-SNAPSHOT</version>
</dependency>
```

Dabei ist darauf zu achten, dass die eingebundene Version exakt der lokal installierten SNAPSHOT-Version entspricht. Nach jeder Änderung am SDK muss `mvn install` erneut ausgeführt und im abhängigen Projekt `mvn clean` (oder ein expliziter Update der Abhängigkeit) durchgeführt werden, damit die geänderten Artefakte geladen werden.

## Deployment einer neuen Version

Vor dem Deployment muss die Version in der `pom.xml` auf die finale Release-Version ohne `-SNAPSHOT` angepasst werden (z. B. `2.0-5p-0`). Auch hier bietet sich „Suchen und Ersetzen" über alle `pom.xml`-Dateien an, um die SNAPSHOT-Suffixe konsistent zu entfernen.

Beim Deployment werden die Test-Module nicht benötigt und müssen daher explizit ausgeschlossen werden. Dazu wird das Property `-pl` in Kombination mit `-am` und einer Liste der zu deployenden Module verwendet, bzw. die nicht zu deployenden Module werden über `-pl` mit vorangestelltem `!` ausgeschlossen. Die Test-Module sind `mcp-test` und `conformance-tests`.

Beispiel für einen Deploy-Aufruf ohne die Test-Module:

```bash
./mvnw \
  -pl mcp-bom,mcp-core,mcp,mcp-json-jackson2,mcp-json-jackson3,mcp-test \
  -am \
  -DskipTests \
  clean deploy
```

Das Deployment erfolgt in das interne Nexus-Repository (`http://svn:8081/nexus/content/repositories/releases/` für Releases bzw. `.../snapshots/` für Snapshot-Versionen), wie in der `distributionManagement`-Sektion der `pom.xml` konfiguriert.

## Änderungen

- **2.0-5p-0** – Unterstützung für benutzerdefinierte Request-Handler in den Server-Buildern (`McpServer`, `McpAsyncServer`, `McpStatelessAsyncServer`) hinzugefügt. Inklusive umfangreicher Tests in `CustomRequestHandlerTests`.

