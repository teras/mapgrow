package com.mapgrow.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.index.strtree.STRtree;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class CountryStore {

    private final List<CountryInfo> countries = new ArrayList<>();
    private final STRtree spatialIndex = new STRtree();

    public void load() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/ne_10m_admin_0_countries.geojson")) {
            if (is == null) throw new IOException("GeoJSON resource not found");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);
            JsonNode features = root.get("features");

            GeometryFactory gf = new GeometryFactory();
            int index = 0;
            for (JsonNode feature : features) {
                JsonNode props = feature.get("properties");
                String name = props.has("NAME") ? props.get("NAME").asText() : "Unknown";
                String iso = props.has("ISO_A3") ? props.get("ISO_A3").asText() : "UNK";

                JsonNode geomNode = feature.get("geometry");
                Geometry geometry = parseGeometry(geomNode, gf);
                if (geometry == null) continue;

                Color color = generateColor(index);
                CountryInfo info = new CountryInfo(name, iso, geometry, color);
                countries.add(info);
                spatialIndex.insert(geometry.getEnvelopeInternal(), info);
                index++;
            }
            spatialIndex.build();
        }
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
     * Find the nearest country to a given point (lon, lat).
     * Searches nearby countries and returns the one with the closest geometry.
     */
    public CountryInfo getNearestCountry(double lon, double lat) {
        GeometryFactory gf = new GeometryFactory();
        org.locationtech.jts.geom.Point point = gf.createPoint(new Coordinate(lon, lat));

        // Search progressively wider areas
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

    private static Color generateColor(int index) {
        float hue = (index * 0.618033988f) % 1.0f;
        float saturation = 0.5f + (index % 3) * 0.15f;
        float brightness = 0.7f + (index % 4) * 0.075f;
        return Color.getHSBColor(hue, saturation, brightness);
    }
}
