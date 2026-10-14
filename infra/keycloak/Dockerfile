# Keycloak does not ship the Oracle JDBC driver. The Keycloak database guide says to put ojdbc17 and orai18n
# in providers/ and run `kc.sh build` with the database set to oracle. Both jars come from Maven Central and are
# pinned by SHA-256, so a tampered download fails the build.
FROM quay.io/keycloak/keycloak:26.8.1

ADD --chmod=644 \
    --checksum=sha256:96010f27fce64c285f9d1aab8f96357b8e00c49c9ad041ecf140c9d7d27eb3fb \
    https://repo1.maven.org/maven2/com/oracle/database/jdbc/ojdbc17/23.26.2.0.0/ojdbc17-23.26.2.0.0.jar \
    /opt/keycloak/providers/

ADD --chmod=644 \
    --checksum=sha256:d0ed0aadf7e319ee8f313a85436919eb75f1012cc20f97d384e72adfbecfe3bd \
    https://repo1.maven.org/maven2/com/oracle/database/nls/orai18n/23.26.2.0.0/orai18n-23.26.2.0.0.jar \
    /opt/keycloak/providers/

# The database vendor is a build-time option in Keycloak.
ENV KC_DB=oracle
RUN /opt/keycloak/bin/kc.sh build
