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
 * Downloads all country boundaries from geoBoundaries API,
 * simplifies them with Douglas-Peucker, and combines into a single GeoJSON.
 * Run with: gradle buildGeoJson
 */
public class BuildGeoJson {

    private static final String API_URL =
            "https://www.geoboundaries.org/api/current/gbOpen/ALL/ADM0/";
    private static final String OUTPUT = "src/main/resources/countries.geojson";
    private static final double TOLERANCE = 0.002;  // ~220m
    private static final int COORD_DECIMALS = 4;    // ~11m precision

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();

        // Fetch country list
        System.out.println("Fetching country list...");
        JsonNode entries = fetchJson(API_URL);
        int total = entries.size();
        System.out.println("  " + total + " countries found");

        // Download and process each country
        ArrayNode features = MAPPER.createArrayNode();
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            JsonNode entry = entries.get(i);
            String iso = entry.has("boundaryISO") ? entry.get("boundaryISO").asText() : "???";
            String url = entry.has("gjDownloadURL") ? entry.get("gjDownloadURL").asText() : null;

            System.out.printf("  [%d/%d] %s...", i + 1, total, iso);
            System.out.flush();

            if (url == null) {
                System.out.println(" NO URL");
                errors.add(iso);
                continue;
            }

            try {
                JsonNode data = fetchJsonRetry(url, 3);
                JsonNode feats = data.get("features");
                if (feats == null) {
                    System.out.println(" NO FEATURES");
                    errors.add(iso);
                    continue;
                }

                for (JsonNode feat : feats) {
                    JsonNode props = feat.get("properties");
                    String name = props.has("shapeName") ? props.get("shapeName").asText() : iso;
                    String group = props.has("shapeGroup") ? props.get("shapeGroup").asText() : iso;

                    // Build simplified feature
                    ObjectNode newFeat = MAPPER.createObjectNode();
                    newFeat.put("type", "Feature");

                    ObjectNode newProps = MAPPER.createObjectNode();
                    newProps.put("NAME", name);
                    newProps.put("ISO_A3", group);
                    newFeat.set("properties", newProps);

                    JsonNode geom = feat.get("geometry");
                    newFeat.set("geometry", simplifyGeometry(geom));
                    features.add(newFeat);
                }
                System.out.println(" OK");
            } catch (Exception e) {
                System.out.println(" FAILED: " + e.getMessage());
                errors.add(iso);
            }
        }

        // Fetch countries missing from the bulk API (e.g. India is excluded
        // from ALL/ADM0 but available via per-country endpoint)
        Set<String> downloadedIsos = new java.util.HashSet<>();
        for (JsonNode feat : features) {
            downloadedIsos.add(feat.get("properties").get("ISO_A3").asText());
        }
        String[] knownMissing = {
                "IND", "PSE", "XKX", "ESH", "TWN", "HKG", "MAC", "SGS", "ALA",
                "BVT", "CCK", "CXR", "HMD", "IOT", "NFK", "SJM", "UMI", "WLF"
        };
        String perCountryUrlTemplate =
                "https://github.com/wmgeolab/geoBoundaries/raw/9469f09/releaseData/gbOpen/%s/ADM0/geoBoundaries-%s-ADM0.geojson";

        for (String iso : knownMissing) {
            if (downloadedIsos.contains(iso)) continue;
            String url = String.format(perCountryUrlTemplate, iso, iso);
            System.out.printf("  [extra] %s...", iso);
            System.out.flush();
            try {
                JsonNode data = fetchJsonRetry(url, 3);
                JsonNode feats = data.get("features");
                if (feats == null || feats.isEmpty()) {
                    System.out.println(" NO DATA");
                    continue;
                }
                for (JsonNode feat : feats) {
                    JsonNode props = feat.get("properties");
                    String name = props.has("shapeName") ? props.get("shapeName").asText() : iso;
                    String group = props.has("shapeGroup") ? props.get("shapeGroup").asText() : iso;
                    ObjectNode newFeat = MAPPER.createObjectNode();
                    newFeat.put("type", "Feature");
                    ObjectNode newProps = MAPPER.createObjectNode();
                    newProps.put("NAME", name);
                    newProps.put("ISO_A3", group);
                    newFeat.set("properties", newProps);
                    newFeat.set("geometry", simplifyGeometry(feat.get("geometry")));
                    features.add(newFeat);
                }
                System.out.println(" OK");
            } catch (Exception e) {
                System.out.println(" SKIP: " + e.getMessage());
            }
        }

        // Also report any errors from the main download
        if (!errors.isEmpty()) {
            System.out.println("Retrying failed countries...");
            for (String iso : new ArrayList<>(errors)) {
                String url = String.format(perCountryUrlTemplate, iso, iso);
                System.out.printf("  [retry] %s...", iso);
                System.out.flush();
                try {
                    JsonNode data = fetchJsonRetry(url, 3);
                    JsonNode feats = data.get("features");
                    if (feats != null) {
                        for (JsonNode feat : feats) {
                            JsonNode props = feat.get("properties");
                            String name = props.has("shapeName") ? props.get("shapeName").asText() : iso;
                            String group = props.has("shapeGroup") ? props.get("shapeGroup").asText() : iso;
                            ObjectNode newFeat = MAPPER.createObjectNode();
                            newFeat.put("type", "Feature");
                            ObjectNode newProps = MAPPER.createObjectNode();
                            newProps.put("NAME", name);
                            newProps.put("ISO_A3", group);
                            newFeat.set("properties", newProps);
                            newFeat.set("geometry", simplifyGeometry(feat.get("geometry")));
                            features.add(newFeat);
                        }
                        errors.remove(iso);
                        System.out.println(" OK");
                    }
                } catch (Exception e) {
                    System.out.println(" FAILED: " + e.getMessage());
                }
            }
        }

        // Write combined GeoJSON
        System.out.printf("%nCombining %d features...%n", features.size());
        ObjectNode collection = MAPPER.createObjectNode();
        collection.put("type", "FeatureCollection");
        collection.set("features", features);

        File output = new File(OUTPUT);
        output.getParentFile().mkdirs();
        MAPPER.writeValue(output, collection);

        long sizeMb = output.length() / (1024 * 1024);
        long elapsed = System.currentTimeMillis() - t0;
        System.out.printf("Written: %s (%d MB, %d features)%n", OUTPUT, sizeMb, features.size());
        if (!errors.isEmpty()) System.out.println("Errors: " + errors);
        System.out.printf("Done in %.1f s%n", elapsed / 1000.0);
    }

    private static JsonNode fetchJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "MapGrow/1.0")
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        return MAPPER.readTree(resp.body());
    }

    private static JsonNode fetchJsonRetry(String url, int retries) throws Exception {
        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                return fetchJson(url);
            } catch (Exception e) {
                if (attempt < retries - 1) Thread.sleep(1000L << attempt);
                else throw e;
            }
        }
        throw new IOException("Unreachable");
    }

    // --- Douglas-Peucker simplification ---

    private static JsonNode simplifyGeometry(JsonNode geom) {
        String type = geom.get("type").asText();
        JsonNode coords = geom.get("coordinates");
        ObjectNode result = MAPPER.createObjectNode();
        result.put("type", type);

        if ("Polygon".equals(type)) {
            result.set("coordinates", simplifyPolygon(coords));
        } else if ("MultiPolygon".equals(type)) {
            ArrayNode polys = MAPPER.createArrayNode();
            for (JsonNode poly : coords) {
                ArrayNode simplified = simplifyPolygon(poly);
                if (simplified.size() > 0 && simplified.get(0).size() >= 4)
                    polys.add(simplified);
            }
            result.set("coordinates", polys);
        } else {
            result.set("coordinates", coords);
        }
        return result;
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

        // Douglas-Peucker using iterative stack
        boolean[] keep = new boolean[coords.length];
        keep[0] = true;
        keep[coords.length - 1] = true;

        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, coords.length - 1});
        double tolSq = TOLERANCE * TOLERANCE;

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
                if (dist > maxDist) {
                    maxDist = dist;
                    maxIdx = i;
                }
            }

            if (maxDist > tolSq) {
                keep[maxIdx] = true;
                if (maxIdx - start > 1) stack.push(new int[]{start, maxIdx});
                if (end - maxIdx > 1) stack.push(new int[]{maxIdx, end});
            }
        }

        // Collect kept points
        List<double[]> result = new ArrayList<>();
        for (int i = 0; i < coords.length; i++) {
            if (keep[i]) result.add(coords[i]);
        }
        // Ensure closed
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
            ptNode.add(round(pt[0]));
            ptNode.add(round(pt[1]));
            arr.add(ptNode);
        }
        return arr;
    }

    private static double round(double val) {
        return BigDecimal.valueOf(val)
                .setScale(COORD_DECIMALS, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
