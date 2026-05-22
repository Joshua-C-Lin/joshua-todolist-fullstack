FROM eclipse-temurin:21-jdk AS build

WORKDIR /app
COPY src ./src
RUN mkdir -p out && javac -d out src/main/java/com/example/todolist/Main.java

FROM eclipse-temurin:21-jre

WORKDIR /app
COPY --from=build /app/out ./out

CMD ["java", "-cp", "out", "com.example.todolist.Main"]

