FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

RUN apt-get update \
    && apt-get install -y curl \
    && rm -rf /var/lib/apt/lists/*

COPY src ./src

# Bundle the JDBC driver into the image so Render does not depend on a host-level jar.
RUN mkdir -p lib out \
    && curl -fsSL https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/9.3.0/mysql-connector-j-9.3.0.jar -o lib/mysql-connector-j.jar \
    && javac -cp lib/mysql-connector-j.jar -d out src/main/java/com/uniongroup/sms/SmsApplication.java \
    && cp -r src/main/resources/* out/

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /app/out ./out
COPY --from=build /app/lib/mysql-connector-j.jar ./lib/mysql-connector-j.jar

ENV PORT=10000

CMD ["sh", "-c", "java -cp out:lib/mysql-connector-j.jar com.uniongroup.sms.SmsApplication ${PORT:-10000}"]
