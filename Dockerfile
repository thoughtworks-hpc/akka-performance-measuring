FROM openjdk:8-jdk-alpine as prepare

WORKDIR /app
RUN wget https://github.com/undera/perfmon-agent/releases/download/2.2.3/ServerAgent-2.2.3.zip

FROM openjdk:8-jdk-alpine

ARG JAR_FILE=target/akka-performance-measuring-1.0-allinone.jar

WORKDIR /usr/local/akka-perf-measuring

COPY ${JAR_FILE} app.jar

COPY docker ./

COPY --from=prepare /app/ServerAgent-2.2.3.zip .
RUN unzip ServerAgent-2.2.3.zip
COPY test.in.txt ./

CMD ["./agent.sh"]