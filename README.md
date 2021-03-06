# GeoPeril

This project is a prototype implementation of an early warning system for
tsunamis, including:

* harvesting of earthquake events from catalogs or APIs
* automatic execution of simulations for events with given thresholds
* remote execution of simulations with EasyWave or HySEA as the simulation processing backend
* queuing of simulation processes with support for multiple remote processing servers
* sending real time notifications about events and simulations via SMS or mail
* individual accounts with different permission levels
* modifying earthquake parameters or creating fictional earthquakes to simulate a scenario

## Google Maps API

You will need a Google Maps API key to activate the core features. Add the key
to following files:

* server/www/embed-data/index.php
* server/www/eqinfo/index.php
* server/www/index.html

## Development environment

You can start a local development environment by using docker containers.

First pull and build all needed images by running `docker-compose` in the
`server` directory:

```bash
docker-compose pull
docker-compose build
```

Now you can start the development environment:

```bash
docker-compose up
```

Some of the files are mounted as a volume and change on the fly in the container.
But if you change for example the `GeoHazardServices` component you need to build
the docker images again with the first command (to compile the classes again).

You can then access the frontend at `http://localhost:8080`.
Default login credentials for an administrative account are `admin`/`admin` and
for a less privileged user `test`/`test`.

## Frontend

The main frontend is based on Bootstrap and jQuery. Also GoogleMaps, Proj4js,
jQuery UI, jQuery Cookie, Font Awesome and Bootstrap Colorpicker are used.

You can find the main frontend at [server/www](server/www).
Some other frontend components are written in PHP or are plain HTML files,
for example [server/www/geofon.php](server/www/geofon.php) and
[server/www/apidoc](server/www/apidoc).

## Backend

### GeoHazardServices

* JavaServlet running on a Tomcat server
* Main class for all exposed methods is [Services.java](server/tomcat/GeoHazardServices/src/main/java/GeoHazardServices/Services.java)
* Schedules and distributes the simulations to the registered worker instances and retrieves their results

### CherryPy

| Script | Role |
| :----- | :----- |
| webguisrv | API for communicating with the DB for frontend components (e.g. to create new events or to retrieve simulation status and results) |
| feedersrv | API for adding entries to data collections |
| msgsrv | Sending messages and notifications |
| datasrv | Retrieving datasets from `eqs` and `evtsets` collection |
| workersrv | API for worker |

### MongoDB

For data management a MongoDB with following collections is used:

#### Collections

| Collection | Contents |
| :----- | :----- |
| comp | Results of computations including geometries of travel times and wave jets |
| eqs | Earthquakes automatically fetched from earthquake catalogs with all their data (lat, lon, magnitude, slip, dip, rake ...) ; also modified or manually created earthquakes will be stored in this collection |
| events | Like a changelog of created/imported earthquakes and simulation progresses |
| evtsets | Eventsets |
| hazard_events | |
| institutions | Institutions a user can belong |
| log | Logging of queries to the `datasrv` API |
| logins | Entries for each user login and also needed for generating usage statistics |
| pickings | Storage of picked values for the stations sealevel data |
| sealeveldata |  |
| settings | Configuration values for the framework itself |
| shared_links | Entries with created permalinks for sharing maps |
| simsealeveldata |  |
| stations | Registered sensor stations |
| users | Contains all user accounts with their settings, permissions and session (if logged in) |

## Worker

The simulations are processed by registered workers and are accessed from the
`GeoHazardServices` component via SSH.

As an example there is an EasyWave (CPU) worker already registered within the
docker-compose environment, but you can also add your own workers.

Following steps are needed to setup a server as a worker and adding it to the
framework:

* add a new user with a home directory (in this example the user is named `worker`)
* install [easyWave](https://gitext.gfz-potsdam.de/geoperil/easyWave) and/or [Tsunami-HySEA](https://edanya.uma.es/hysea/index.php/models/tsunami-hysea) for the newly created user (so that the user can execute the binaries)
* create directories ending with a number counting from 0, e.g. `/home/worker/easywave/web/worker0` (the number of directories depends on the desired slot counts -> number of parallel simulations per worker)
* add the public key of the tomcat8 user to the `authorized_keys` file of the worker
* get the `known_hosts` entry of the worker with `ssh-keyscan <hostname or IP>` and add it the `known_hosts` file of the tomcat8 user

To register the worker to the framework you need to add an entry in the
`settings` collection with following parameters:

```json
{
    "args": "Enter additional command line argument for EasyWave here",
    "count": 2,
    "dir": "/home/worker/EasyWave/web/worker",
    "hardware": "Describe the specifications of the GPU",
    "host": "Enter IP address or hostname here",
    "priority": 10,
    "remote": true,
    "slots": 0,
    "type": "worker",
    "user": "worker"
}
```

The parameter `count` means the number of parallel executions of the program.
So for a count of 2 there will be a maximum number of 2 parallel simulations
with the working directories `/home/worker/EasyWave/web/worker0` and
`/home/worker/EasyWave/web/worker1`.

For the docker-compose environment you can therefore modify the file
[server/mongodb-data/example-data/settings.json](server/mongodb-data/example-data/settings.json)
and then (re)start the docker environment again with `docker-compose up`.
