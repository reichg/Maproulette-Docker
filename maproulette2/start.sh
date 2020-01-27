#!/bin/sh

export MR_APPLICATION_SECRET="lame-ducks-forever" # change to unique string
export MR_DATABASE_URL="jdbc:postgresql://localhost:5432/mp_dev?user=osm&password=osm" # database access string
export MR_OSM_SERVER="http://api06.dev.openstreetmap.org" # OSM dev server, overrides default which is production
export MR_OAUTH_CONSUMER_KEY="8kOhMqcXVAmD3ZoBdjNgcmd93ErztPnIPpc0xjzM"
export MR_OAUTH_CONSUMER_SECRET="bAesEwMMwZO6wMgFMqAudZ12N5caQvAAsx3FbkFc"
export MR_SUPER_KEY="test" # API key to test superuser operations with
export MR_SUPER_ACCOUNTS="437" # OSM account id(s) for superuser privileges, comma separated.

activator run