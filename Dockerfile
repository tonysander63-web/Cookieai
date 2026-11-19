
FROM eclipse-temurin:21-jdk

WORKDIR /app

COPY CookieAI.java .

RUN javac CookieAI.java

CMD ["java", "CookieAI"]
