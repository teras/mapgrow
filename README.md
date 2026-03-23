# 🌍 MapGrow

**Interactive map visualization that grows country territories across the seas.**

MapGrow fills ocean areas by expanding country colors outward from coastlines using a frontier expansion algorithm. Watch as nations organically claim the seas around them in real-time!

![Java](https://img.shields.io/badge/Java-17-orange)
![License](https://img.shields.io/badge/License-MIT-blue)

## ✨ Features

- 🗺️ **Interactive Map** — Pan, zoom, and explore using OpenStreetMap tiles
- 🎨 **Frontier Expansion** — Weighted voting algorithm fills sea pixels from neighboring land
- 🔄 **Real-time Visualization** — Watch the expansion unfold at 60fps with progress tracking
- 🌈 **Multiple View Modes** — Map+Overlay, Land/Sea, Colored Land, Natural Earth
- 🧮 **Smart Color Assignment** — Graph coloring with flag-based color preferences and simulated annealing

## 📦 Download

Pre-built packages with embedded JRE are available on the [Releases](../../releases) page — no Java installation required.

| Platform | Package | How to run |
|----------|---------|------------|
| 🐧 Linux | `mapgrow-*-linux.tar.gz` | Extract and run `./mapgrow` |
| 🐧 Linux | `mapgrow_*_amd64.deb` | Install with `sudo dpkg -i` |
| 🍎 macOS (Apple Silicon) | `mapgrow-*-macos-arm64.zip` | Extract and double-click `MapGrow.app` |
| 🍎 macOS (Intel) | `mapgrow-*-macos-x64.zip` | Extract and double-click `MapGrow.app` |
| 🪟 Windows | `mapgrow-*-windows.zip` | Extract and run `MapGrow.exe` |

> **macOS note:** The app is signed and notarized. If macOS still shows a warning on first launch, right-click `MapGrow.app` and select **Open**.

## 🚀 Build from Source

### Prerequisites

- Java 17+
- Gradle

### Build & Run

```bash
# Prepare data (first time only)
gradle buildGeoJson
gradle buildEezZones
gradle computeColors

# Run the application
gradle run
```

### Fat JAR

```bash
gradle fatJar
java -jar build/libs/mapgrow-all.jar
```

## 🎮 How to Use

1. **Pan** — Drag the map
2. **Zoom** — Scroll wheel
3. **Start** — Click `Start` to begin the expansion
4. **Stop** — Cancel the expansion at any point
5. **Reset** — Clear the overlay and start over
6. Switch between view modes using the radio buttons

## 🧠 How It Works

The core algorithm uses **frontier expansion with weighted voting**:

1. Identify sea pixels adjacent to colored land (the "frontier")
2. For each frontier pixel, tally weighted votes from colored neighbors (cardinal = 1000, diagonal = 707)
3. Assign the winning country's color
4. Add newly colored pixels' uncolored neighbors to the frontier
5. Repeat until all sea is claimed

Country colors are pre-computed using a **graph coloring** approach with 6 color families × 2 shades, optimized via parallel simulated annealing to match each country's flag colors.

## 🌐 Data Sources & Attribution

MapGrow relies on the following open data sources:

| Source | What we use it for | License |
|--------|-------------------|---------|
| [OpenStreetMap](https://www.openstreetmap.org/) | Base map tiles (`tile.openstreetmap.org`) for the interactive map view | [ODbL](https://www.openstreetmap.org/copyright) — © OpenStreetMap contributors |
| [CARTO Basemaps](https://carto.com/basemaps/) | Light no-labels tiles (`basemaps.cartocdn.com/light_nolabels`) for land/sea pixel classification | [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/) |
| [geoBoundaries](https://www.geoboundaries.org/) | Country boundary polygons (ADM0) via the [gbOpen API](https://www.geoboundaries.org/api/current/gbOpen/ALL/ADM0/) — simplified and stored as `countries.geojson` | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) |
| [Marine Regions](https://www.marineregions.org/) | EEZ + Land union zones via [WFS](https://geo.vliz.be/geoserver/MarineRegions/wfs) (`eez_land` layer) for offshore country assignment | [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) — Flanders Marine Institute (VLIZ) |

### 📡 Runtime network requests

- **Map tiles** are fetched live from OpenStreetMap and CARTO CDN during map interaction
- **Country & EEZ data** are downloaded once during the build step (`gradle buildGeoJson` / `gradle buildEezZones`) and cached locally as GeoJSON files

## 🛠️ Tech Stack

| Component | Technology |
|-----------|-----------|
| UI | Java Swing + [FlatLaf](https://www.formdev.com/flatlaf/) |
| Geometry | [JTS Topology Suite](https://github.com/locationtech/jts) |
| JSON/GeoJSON | [Jackson](https://github.com/FasterXML/jackson) |
| Map Tiles | [OpenStreetMap](https://www.openstreetmap.org/) / [CARTO](https://carto.com/basemaps/) |
| Build | Gradle |

## 📁 Project Structure

```
src/main/java/com/mapgrow/
├── MapGrowApp.java              # Main application window
├── geo/                         # Country data & spatial indexing
├── map/                         # Map rendering & tile management
├── processing/                  # Expansion algorithm & rasterization
├── ui/                          # Controls & dialogs
└── tools/                       # Data preparation utilities
```

## 📄 License

[MIT](LICENSE)
