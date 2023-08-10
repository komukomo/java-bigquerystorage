FROM maven:3.8.7-openjdk-18-slim

WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn package

COPY docker-entrypoint.sh ./
ENTRYPOINT ["./docker-entrypoint.sh"]
# mvn exec:java -Dexec.mainClass="com.example.bigquerystorage.StorageArrowSample"  -Dexec.args="plaid-research-database"