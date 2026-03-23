package com.mapgrow.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class CountryStore {

    // Must match ComputeColors PALETTE: [family][shade]
    private static final Color[][] PALETTE = {
            // Blue: normal, dark
            {new Color(30, 120, 220), new Color(0, 50, 130)},
            // Red: normal, dark
            {new Color(210, 40, 50), new Color(140, 0, 20)},
            // Green: normal, dark
            {new Color(30, 170, 80), new Color(0, 90, 40)},
            // Yellow: normal, dark
            {new Color(255, 210, 30), new Color(200, 160, 0)},
            // Orange: normal, dark
            {new Color(255, 140, 20), new Color(200, 90, 0)},
            // Purple: normal, dark
            {new Color(130, 60, 180), new Color(70, 20, 110)},
    };

    private static final GeometryFactory GF = new GeometryFactory();

    private final List<CountryInfo> countries = new ArrayList<>();
    private final STRtree spatialIndex = new STRtree();

    // EEZ+Land zones for fallback point-in-polygon lookup
    private record ZoneEntry(String iso, PreparedGeometry prepared) {}
    private final STRtree zoneIndex = new STRtree();
    private final Map<String, CountryInfo> countryByIso = new HashMap<>();

    public void load() throws IOException {
        Map<String, String> colorMap = loadColorMap();

        // Load country boundaries
        try (InputStream is = getClass().getResourceAsStream("/countries.geojson")) {
            if (is == null) throw new IOException("countries.geojson not found");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode features = mapper.readTree(is).get("features");

            int index = 0;
            for (JsonNode feature : features) {
                JsonNode props = feature.get("properties");
                String name = props.has("NAME") ? props.get("NAME").asText() : "Unknown";
                String iso = props.has("ISO_A3") ? props.get("ISO_A3").asText() : "UNK";
                Geometry geometry = parseGeometry(feature.get("geometry"), GF);
                if (geometry == null) continue;

                Color color = getColor(iso, index, colorMap);
                CountryInfo info = new CountryInfo(name, iso, geometry, color);
                countries.add(info);
                spatialIndex.insert(geometry.getEnvelopeInternal(), info);
                countryByIso.put(iso, info);
                index++;
            }
            spatialIndex.build();
        }

        // Load EEZ+Land zones for fallback lookup
        loadEezZones();
    }

    private void loadEezZones() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/eez_zones.geojson")) {
            if (is == null) return; // optional — falls back to getNearestCountry
            ObjectMapper mapper = new ObjectMapper();
            JsonNode features = mapper.readTree(is).get("features");

            for (JsonNode feature : features) {
                String iso = feature.get("properties").get("ISO_A3").asText();
                Geometry geometry = parseGeometry(feature.get("geometry"), GF);
                if (geometry == null || !geometry.isValid()) continue;
                PreparedGeometry prepared = PreparedGeometryFactory.prepare(geometry);
                ZoneEntry entry = new ZoneEntry(iso, prepared);
                zoneIndex.insert(geometry.getEnvelopeInternal(), entry);
            }
            zoneIndex.build();
        }
    }

    private static Color getColor(String iso, int index, Map<String, String> colorMap) {
        String val = colorMap.get(iso);
        if (val != null && val.contains(".")) {
            String[] parts = val.split("\\.");
            int family = Integer.parseInt(parts[0]);
            int shade = Integer.parseInt(parts[1]);
            if (family < PALETTE.length && shade < PALETTE[family].length) {
                return PALETTE[family][shade];
            }
        }
        // Fallback: old format (single int) or missing
        int flat = index % (PALETTE.length * PALETTE[0].length);
        return PALETTE[flat / PALETTE[0].length][flat % PALETTE[0].length];
    }

    private Map<String, String> loadColorMap() throws IOException {
        Map<String, String> map = new HashMap<>();
        try (InputStream is = getClass().getResourceAsStream("/country_colors.properties")) {
            if (is == null) return map;
            Properties props = new Properties();
            props.load(is);
            for (String key : props.stringPropertyNames()) {
                map.put(key, props.getProperty(key));
            }
        }
        return map;
    }

    public List<CountryInfo> getCountriesIn(double minLon, double minLat, double maxLon, double maxLat) {
        Envelope searchEnv = new Envelope(minLon, maxLon, minLat, maxLat);
        @SuppressWarnings("unchecked")
        List<CountryInfo> candidates = spatialIndex.query(searchEnv);
        return candidates;
    }

    public List<CountryInfo> getAllCountries() {
        return countries;
    }

    /**
     * Find which country a point belongs to.
     * First tries EEZ+Land zone (point-in-polygon), then falls back to nearest country.
     */
    public CountryInfo getNearestCountry(double lon, double lat) {
        Point point = GF.createPoint(new Coordinate(lon, lat));

        // Try EEZ zone lookup first (point-in-polygon)
        CountryInfo zoneResult = findZoneCountry(point);
        if (zoneResult != null) return zoneResult;

        // Fallback: nearest country by geometry distance
        for (double buffer = 1.0; buffer <= 30.0; buffer *= 2) {
            Envelope searchEnv = new Envelope(lon - buffer, lon + buffer, lat - buffer, lat + buffer);
            @SuppressWarnings("unchecked")
            List<CountryInfo> candidates = spatialIndex.query(searchEnv);
            if (!candidates.isEmpty()) {
                CountryInfo nearest = null;
                double minDist = Double.MAX_VALUE;
                for (CountryInfo c : candidates) {
                    double dist = c.geometry().distance(point);
                    if (dist < minDist) {
                        minDist = dist;
                        nearest = c;
                    }
                }
                if (nearest != null) return nearest;
            }
        }
        return null;
    }

    private CountryInfo findZoneCountry(Point point) {
        Envelope env = new Envelope(point.getCoordinate());
        @SuppressWarnings("unchecked")
        List<ZoneEntry> candidates = zoneIndex.query(env);
        for (ZoneEntry entry : candidates) {
            if (entry.prepared.contains(point)) {
                return countryByIso.get(entry.iso);
            }
        }
        return null;
    }

    private static Geometry parseGeometry(JsonNode geomNode, GeometryFactory gf) {
        String type = geomNode.get("type").asText();
        JsonNode coords = geomNode.get("coordinates");
        return switch (type) {
            case "Polygon" -> parsePolygon(coords, gf);
            case "MultiPolygon" -> parseMultiPolygon(coords, gf);
            default -> null;
        };
    }

    private static Polygon parsePolygon(JsonNode coords, GeometryFactory gf) {
        LinearRing shell = parseRing(coords.get(0), gf);
        LinearRing[] holes = new LinearRing[coords.size() - 1];
        for (int i = 1; i < coords.size(); i++) {
            holes[i - 1] = parseRing(coords.get(i), gf);
        }
        return gf.createPolygon(shell, holes);
    }

    private static MultiPolygon parseMultiPolygon(JsonNode coords, GeometryFactory gf) {
        Polygon[] polygons = new Polygon[coords.size()];
        for (int i = 0; i < coords.size(); i++) {
            polygons[i] = parsePolygon(coords.get(i), gf);
        }
        return gf.createMultiPolygon(polygons);
    }

    private static LinearRing parseRing(JsonNode ringCoords, GeometryFactory gf) {
        Coordinate[] coordinates = new Coordinate[ringCoords.size()];
        for (int i = 0; i < ringCoords.size(); i++) {
            JsonNode point = ringCoords.get(i);
            coordinates[i] = new Coordinate(point.get(0).asDouble(), point.get(1).asDouble());
        }
        return gf.createLinearRing(coordinates);
    }
}
