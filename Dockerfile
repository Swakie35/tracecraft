FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src ./src
RUN mkdir -p build/classes && find src/main/java -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 -d build/classes

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/classes ./classes
ENV BIND_HOST=0.0.0.0
USER 10001:10001
EXPOSE 8081
CMD ["java", "-cp", "classes", "dev.swaraj.tracecraft.Server", "8081"]
