# Chicagoland Commuter

A web app, installable to the home screen on iPhone and Android, that plans Chicagoland trips across
CTA, Metra, Pace, Divvy, bike lanes and driving. The first milestone is a park-and-ride commute:
drive from Elgin to a Metra station, ride Metra to Union Station, then take a CTA bus to UIC.

**Stack:** Java 25 · Spring Boot 4 · Vaadin 25 · PostgreSQL 17 + PostGIS · Flyway · Maven · JUnit 5 + Testcontainers · Docker

## Run it on your computer

You need **Java 25**, **Docker Desktop** (running), **Git** and **IntelliJ IDEA**. You don't need to install Maven or PostgreSQL separately.

1. Clone the project:
   ```
   git clone https://github.com/RishiDShah/Chicagoland-Commuter.git
   ```
2. In IntelliJ: **File → Open** and choose the `Chicagoland-Commuter` folder. Wait for it to finish indexing and downloading libraries (bottom-right progress bar; a few minutes the first time).
3. Open `src/main/java/com/chicagoland/commuter/CommuterApplication.java` and click the green ▶ next to `public class CommuterApplication`.
4. The first run downloads the PostGIS database image (~200 MB) and starts it in Docker automatically. Your browser then opens at <http://localhost:8080>.

The home screen should say **"Database connected · PostGIS 3.5.x"** at the bottom.

### From the terminal instead

```
.\mvnw spring-boot:run      # Windows PowerShell
./mvnw spring-boot:run      # macOS / Linux
```

### Run the tests

```
.\mvnw verify
```

The tests start a throwaway PostGIS database in Docker, so Docker Desktop must be running.

### Stop the database

The database keeps running after you stop the app, so restarts are faster. To stop it:

```
docker compose down
```

Add `-v` to also delete its data.

## Project layout

| Path | What it is |
|---|---|
| `src/main/java/.../CommuterApplication.java` | Starts the app; makes it installable to the home screen |
| `src/main/java/.../ui/` | Screens (Vaadin views) |
| `src/main/resources/db/migration/` | Database changes, applied in order by Flyway |
| `src/main/resources/application.properties` | App settings |
| `compose.yaml` | Local PostgreSQL + PostGIS database |
| `src/test/java/` | Tests |

## Roadmap

1. ~~Project setup: app, database, tests~~
2. Load CTA, Metra and Pace schedules (GTFS) into PostGIS
3. RAPTOR transit routing: Bartlett → Union Station → UIC
4. Drive leg to every Metra park-and-ride station (GraphHopper)
5. Live data: CTA Bus Tracker, Metra delays, re-plan the bus leg
6. Saved commutes and leave-by push alerts
7. Bike routing with protected-lane weighting and Divvy availability
8. Jenkins pipeline and deployment to an Oracle Always Free VM
