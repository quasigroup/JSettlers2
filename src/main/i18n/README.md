# Update translations

    po2prop -t src/main/resources/resources/strings/client/data.properties src/main/i18n/client/ build/client
    po2prop -t src/main/resources/resources/strings/server/toClient.properties src/main/i18n/server/ build/server

Compare generated properties files with the ones in rc/main/resources/resources/strings.
