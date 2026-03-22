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

    // Must match the palette used by ComputeColors
    private static final Color[] PALETTE = {
            new Color(0, 91, 187),    // 0: Blue
            new Color(206, 17, 38),   // 1: Red
            new Color(0, 154, 68),    // 2: Green
            new Color(255, 206, 0),   // 3: Yellow
            new Color(255, 130, 0),   // 4: Orange
            new Color(112, 48, 160),  // 5: Purple
            new Color(0, 158, 150),   // 6: Teal
            new Color(180, 130, 70),  // 7: Tan
            new Color(220, 100, 150), // 8: Rose
            new Color(100, 120, 180), // 9: Steel Blue
            new Color(160, 180, 60),  // 10: Olive
            new Color(170, 80, 80),   // 11: Brick Red
    };

    private final List<CountryInfo> countries = new ArrayList<>();
    private final STRtree spatialIndex = new STRtree();

    // EEZ+Land zones for fallback point-in-polygon lookup
    private record ZoneEntry(String iso, PreparedGeometry prepared) {}
    private final STRtree zoneIndex = new STRtree();
    private final Map<String, CountryInfo> countryByIso = new HashMap<>();

    public void load() throws IOException {
        Map<String, Integer> colorMap = loadColorMap();
        GeometryFactory gf = new GeometryFactory();

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
                Geometry geometry = parseGeometry(feature.get("geometry"), gf);
                if (geometry == null) continue;

                int colorIdx = colorMap.getOrDefault(iso, index % PALETTE.length);
                Color color = PALETTE[colorIdx % PALETTE.length];
                CountryInfo info = new CountryInfo(name, iso, geometry, color);
                countries.add(info);
                spatialIndex.insert(geometry.getEnvelopeInternal(), info);
                countryByIso.put(iso, info);
                index++;
            }
            spatialIndex.build();
        }

        // Load EEZ+Land zones for fallback lookup
        loadEezZones(gf);
    }

    private void loadEezZones(GeometryFactory gf) throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/eez_zones.geojson")) {
            if (is == null) return; // optional — falls back to getNearestCountry
            ObjectMapper mapper = new ObjectMapper();
            JsonNode features = mapper.readTree(is).get("features");

            for (JsonNode feature : features) {
                String iso = feature.get("properties").get("ISO_A3").asText();
                Geometry geometry = parseGeometry(feature.get("geometry"), gf);
                if (geometry == null || !geometry.isValid()) continue;
                PreparedGeometry prepared = PreparedGeometryFactory.prepare(geometry);
                ZoneEntry entry = new ZoneEntry(iso, prepared);
                zoneIndex.insert(geometry.getEnvelopeInternal(), entry);
            }
            zoneIndex.build();
        }
    }

    private Map<String, Integer> loadColorMap() throws IOException {
        Map<String, Integer> map = new HashMap<>();
        try (InputStream is = getClass().getResourceAsStream("/country_colors.properties")) {
            if (is == null) return map;
            Properties props = new Properties();
            props.load(is);
            for (String key : props.stringPropertyNames()) {
                map.put(key, Integer.parseInt(props.getProperty(key)));
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
        GeometryFactory gf = new GeometryFactory();
        Point point = gf.createPoint(new Coordinate(lon, lat));

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

    private Geometry parseGeometry(JsonNode geomNode, GeometryFactory gf) {
        String type = geomNode.get("type").asText();
        JsonNode coords = geomNode.get("coordinates");
        return switch (type) {
            case "Polygon" -> parsePolygon(coords, gf);
            case "MultiPolygon" -> parseMultiPolygon(coords, gf);
            default -> null;
        };
    }

    private Polygon parsePolygon(JsonNode coords, GeometryFactory gf) {
        LinearRing shell = parseRing(coords.get(0), gf);
        LinearRing[] holes = new LinearRing[coords.size() - 1];
        for (int i = 1; i < coords.size(); i++) {
            holes[i - 1] = parseRing(coords.get(i), gf);
        }
        return gf.createPolygon(shell, holes);
    }

    private MultiPolygon parseMultiPolygon(JsonNode coords, GeometryFactory gf) {
        Polygon[] polygons = new Polygon[coords.size()];
        for (int i = 0; i < coords.size(); i++) {
            polygons[i] = parsePolygon(coords.get(i), gf);
        }
        return gf.createMultiPolygon(polygons);
    }

    private LinearRing parseRing(JsonNode ringCoords, GeometryFactory gf) {
        Coordinate[] coordinates = new Coordinate[ringCoords.size()];
        for (int i = 0; i < ringCoords.size(); i++) {
            JsonNode point = ringCoords.get(i);
            coordinates[i] = new Coordinate(point.get(0).asDouble(), point.get(1).asDouble());
        }
        return gf.createLinearRing(coordinates);
    }
}
