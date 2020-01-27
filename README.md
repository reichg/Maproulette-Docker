# maproulette-local-docker

Clone Maproulette repos to their respective folders.

## maproulette3:
* Clone MapRoulette 3 `git clone https://github.com/maproulette/maproulette3.git`
* `cd` into the newly created directory `cd maproulette3`
Create a `.env.development.local` file and then look through `.env` at the
   available configuration options and override any desired settings in your
   new `.env.development.local`
  
## maproulette2:
### Register an OAuth app with OSM

Before beginning, you'll need to register an app with OpenStreetMap to get a consumer key and secret key. For development and testing, you may wish to do this on the [OSM dev server](http://master.apis.dev.openstreetmap.org) (you will need to setup a new user account if you have't used the dev server before).

To register your app, login to your account, go to "My Settings", click on "oauth settings", and then click "Register your Application" near the bottom. Give your app a name and application URL (you can simply use http://localhost:9000 if desired) and leave the other URL fields blank. In the permissions section, check "read their user preferences" and "modify the map" and then click the "Register" button at the bottom to get your consumer and secret keys. Be sure to take note of them.

Remember that you need to create the OAuth application on the OSM server that you will be testing against.

For more details on the app registration process, see the [OSM OAuth wiki page](http://wiki.openstreetmap.org/wiki/OAuth).

### Server Configuration

* Clone MapRoulette 2 `git clone https://github.com/maproulette/maproulette2.git`
* `cd` into the newly created directory `cd maproulette2`
* Create a configuration file by copying the template file `cp conf/dev.conf.example conf/dev.conf`
* Open `dev.conf` in a text editor and change at least the following entries:
    * `super.key`: a randomly chosen API key for superuser access
    * `super.accounts`: a comma-separated list of OSM accound IDs whose corresponding MapRoulette users will have superuser access. Can be an empty string.
    * `mapillary.clientId`: a [Mapillary Client ID](https://www.mapillary.com/dashboard/developers), needed if you want to use any of the Mapillary integrations.
    * `osm.consumerKey` and `osm.consumerSecret`: the OAuth keys from your OSM OAuth app you created earlier.
* Save `dev.conf`

## maproulette2-docker:
* Clone MapRoulette 2 Docker `git clone https://github.com/maproulette/maproulette2-docker.git`


You can now run `docker-compose up`
