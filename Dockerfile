FROM gradle:7-jdk8-jammy AS TEMP_BUILD_IMAGE
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . /home/gradle/src
USER root
RUN chown -R gradle /home/gradle/src
RUN gradle serverJarWithDependencies -Ppython_command=python3

FROM adoptopenjdk/openjdk8:jre
ENV APP_HOME=/app

RUN mkdir $APP_HOME
WORKDIR $APP_HOME
COPY --from=TEMP_BUILD_IMAGE /home/gradle/src/build/libs/JSettlersServerWithDeps.jar .

EXPOSE 8880
ENTRYPOINT ["java", "-jar", "JSettlersServerWithDeps.jar"]
