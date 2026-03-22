package com.mapgrow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

/**
 * Downloads EEZ+Land union data from Marine Regions WFS and builds
 * a simplified GeoJSON for country zone lookup (point-in-polygon).
 * This is used instead of getNearestCountry() to correctly assign
 * small islands like Ro and Strongyli to Greece.
 *
 * Run with: gradle buildEezZones
 */
public class BuildEezZones {

    private static final String WFS_URL =
            "https://geo.vliz.be/geoserver/MarineRegions/wfs?" +
            "service=WFS&version=1.0.0&request=GetFeature" +
            "&typeName=MarineRegions:eez_land&maxFeatures=500" +
            "&outputformat=application/json";
    private static final String OUTPUT = "src/main/resources/eez_zones.geojson";
    private static final double TOLERANCE = 0.01;  // ~1km simplification
    private static final int COORD_DECIMALS = 3;   // ~110m precision

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();

        // Download
        System.out.println("Downloading EEZ+Land zones from Marine Regions...");
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(WFS_URL))
                .header("User-Agent", "MapGrow/1.0")
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new IOException("HTTP " + resp.statusCode());

        JsonNode root = MAPPER.readTree(resp.body());
        JsonNode features = root.get("features");
        System.out.println("  " + features.size() + " features downloaded");

        // Merge features by ISO code (sovereign), simplify, and output
        // Group by effective ISO: use iso_ter1, fallback to iso_sov1
        Map<String, List<JsonNode>> byIso = new LinkedHashMap<>();
        Map<String, String> isoToName = new HashMap<>();

        for (JsonNode feat : features) {
            JsonNode props = feat.get("properties");
            String iso = getIso(props);
            if (iso == null) continue;

            String name = getNullableText(props, "territory1");
            if (name == null) name = getNullableText(props, "sovereign1");
            if (name == null) name = iso;

            byIso.computeIfAbsent(iso, k -> new ArrayList<>()).add(feat);
            // Keep the sovereign name if we're using sovereign ISO
            if (!isoToName.containsKey(iso)) {
                String sov = getNullableText(props, "sovereign1");
                isoToName.put(iso, sov != null ? sov : name);
            }
        }

        System.out.println("  " + byIso.size() + " unique countries after merging");

        // Build output
        ArrayNode outFeatures = MAPPER.createArrayNode();
        for (var entry : byIso.entrySet()) {
            String iso = entry.getKey();
            List<JsonNode> feats = entry.getValue();

            ObjectNode outFeat = MAPPER.createObjectNode();
            outFeat.put("type", "Feature");

            ObjectNode outProps = MAPPER.createObjectNode();
            outProps.put("ISO_A3", iso);
            outProps.put("NAME", isoToName.get(iso));
            outFeat.set("properties", outProps);

            // Merge all polygons into one MultiPolygon
            ArrayNode allPolys = MAPPER.createArrayNode();
            for (JsonNode feat : feats) {
                JsonNode geom = feat.get("geometry");
                String type = geom.get("type").asText();
                JsonNode coords = geom.get("coordinates");

                if ("MultiPolygon".equals(type)) {
                    for (JsonNode poly : coords) {
                        ArrayNode simplified = simplifyPolygon(poly);
                        if (simplified.size() > 0) allPolys.add(simplified);
                    }
                } else if ("Polygon".equals(type)) {
                    ArrayNode simplified = simplifyPolygon(coords);
                    if (simplified.size() > 0) allPolys.add(simplified);
                }
            }

            ObjectNode outGeom = MAPPER.createObjectNode();
            outGeom.put("type", "MultiPolygon");
            outGeom.set("coordinates", allPolys);
            outFeat.set("geometry", outGeom);

            outFeatures.add(outFeat);
        }

        ObjectNode collection = MAPPER.createObjectNode();
        collection.put("type", "FeatureCollection");
        collection.set("features", outFeatures);

        File output = new File(OUTPUT);
        output.getParentFile().mkdirs();
        MAPPER.writeValue(output, collection);

        long sizeKb = output.length() / 1024;
        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("Written: %s (%d KB, %d countries)%n",
                OUTPUT, sizeKb, outFeatures.size());
        System.out.printf("Done in %.1f s%n", elapsed / 1000.0);
    }

    private static String getIso(JsonNode props) {
        String iso = getNullableText(props, "iso_ter1");
        if (iso != null) return iso;
        return getNullableText(props, "iso_sov1");
    }

    private static String getNullableText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) return null;
        String val = node.get(field).asText();
        return val.isEmpty() ? null : val;
    }

    private static ArrayNode simplifyPolygon(JsonNode polyCoords) {
        ArrayNode result = MAPPER.createArrayNode();
        for (JsonNode ring : polyCoords) {
            ArrayNode simplified = simplifyRing(ring);
            if (simplified.size() >= 4)
                result.add(simplified);
        }
        return result;
    }

    private static ArrayNode simplifyRing(JsonNode ringNode) {
        double[][] coords = new double[ringNode.size()][2];
        for (int i = 0; i < ringNode.size(); i++) {
            coords[i][0] = ringNode.get(i).get(0).asDouble();
            coords[i][1] = ringNode.get(i).get(1).asDouble();
        }

        if (coords.length <= 4) return coordsToArray(coords);

        // Douglas-Peucker (iterative)
        boolean[] keep = new boolean[coords.length];
        keep[0] = true;
        keep[coords.length - 1] = true;
        double tolSq = TOLERANCE * TOLERANCE;

        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, coords.length - 1});

        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int start = range[0], end = range[1];
            double maxDist = 0;
            int maxIdx = start;
            double x1 = coords[start][0], y1 = coords[start][1];
            double x2 = coords[end][0], y2 = coords[end][1];
            double dx = x2 - x1, dy = y2 - y1;
            double lenSq = dx * dx + dy * dy;

            for (int i = start + 1; i < end; i++) {
                double px = coords[i][0], py = coords[i][1];
                double dist;
                if (lenSq == 0) {
                    dist = (px - x1) * (px - x1) + (py - y1) * (py - y1);
                } else {
                    double t = Math.max(0, Math.min(1, ((px - x1) * dx + (py - y1) * dy) / lenSq));
                    double ex = px - x1 - t * dx, ey = py - y1 - t * dy;
                    dist = ex * ex + ey * ey;
                }
                if (dist > maxDist) { maxDist = dist; maxIdx = i; }
            }
            if (maxDist > tolSq) {
                keep[maxIdx] = true;
                if (maxIdx - start > 1) stack.push(new int[]{start, maxIdx});
                if (end - maxIdx > 1) stack.push(new int[]{maxIdx, end});
            }
        }

        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < coords.length; i++) {
            if (keep[i]) result.add(coords[i]);
        }
        if (result.get(result.size() - 1)[0] != result.get(0)[0]
                || result.get(result.size() - 1)[1] != result.get(0)[1]) {
            result.add(result.get(0));
        }
        return coordsToArray(result.toArray(new double[0][]));
    }

    private static ArrayNode coordsToArray(double[][] coords) {
        ArrayNode arr = MAPPER.createArrayNode();
        for (double[] pt : coords) {
            ArrayNode ptNode = MAPPER.createArrayNode();
            ptNode.add(BigDecimal.valueOf(pt[0]).setScale(COORD_DECIMALS, RoundingMode.HALF_UP).doubleValue());
            ptNode.add(BigDecimal.valueOf(pt[1]).setScale(COORD_DECIMALS, RoundingMode.HALF_UP).doubleValue());
            arr.add(ptNode);
        }
        return arr;
    }
}
