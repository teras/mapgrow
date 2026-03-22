package com.mapgrow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.io.*;
import java.util.*;

/**
 * Offline tool: computes graph coloring for country boundaries.
 * Reads countries.geojson, builds adjacency, assigns colors, writes country_colors.properties.
 * Run with: gradle run -PmainClass=com.mapgrow.tools.ComputeColors
 */
public class ComputeColors {

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

    // Preferred colors per country (ordered by priority, flag-based)
    private static final Map<String, int[]> PREFERRED = new HashMap<>();
    static {
        // Europe
        PREFERRED.put("GRC", new int[]{0});
        PREFERRED.put("TUR", new int[]{1});
        PREFERRED.put("FRA", new int[]{0, 1});
        PREFERRED.put("DEU", new int[]{3, 1});
        PREFERRED.put("ITA", new int[]{2, 1});
        PREFERRED.put("ESP", new int[]{3, 1});
        PREFERRED.put("PRT", new int[]{1, 2});
        PREFERRED.put("GBR", new int[]{0, 1});
        PREFERRED.put("IRL", new int[]{2, 4});
        PREFERRED.put("NLD", new int[]{4, 0});
        PREFERRED.put("BEL", new int[]{3, 1});
        PREFERRED.put("CHE", new int[]{1});
        PREFERRED.put("AUT", new int[]{1});
        PREFERRED.put("RUS", new int[]{1, 0});
        PREFERRED.put("POL", new int[]{1});
        PREFERRED.put("CZE", new int[]{0, 1});
        PREFERRED.put("SVK", new int[]{0, 1});
        PREFERRED.put("HUN", new int[]{1, 2});
        PREFERRED.put("ROU", new int[]{3, 0});
        PREFERRED.put("BGR", new int[]{2, 1});
        PREFERRED.put("SRB", new int[]{1, 0});
        PREFERRED.put("HRV", new int[]{1, 0});
        PREFERRED.put("SVN", new int[]{0, 1});
        PREFERRED.put("BIH", new int[]{0, 3});
        PREFERRED.put("MNE", new int[]{1, 3});
        PREFERRED.put("ALB", new int[]{1});
        PREFERRED.put("MKD", new int[]{1, 3});
        PREFERRED.put("UKR", new int[]{0, 3});
        PREFERRED.put("BLR", new int[]{1, 2});
        PREFERRED.put("MDA", new int[]{0, 3});
        PREFERRED.put("LTU", new int[]{3, 2});
        PREFERRED.put("LVA", new int[]{11, 1});
        PREFERRED.put("EST", new int[]{0});
        PREFERRED.put("FIN", new int[]{0});
        PREFERRED.put("SWE", new int[]{0, 3});
        PREFERRED.put("NOR", new int[]{1, 0});
        PREFERRED.put("DNK", new int[]{1});
        PREFERRED.put("ISL", new int[]{0, 1});
        // Middle East / Central Asia
        PREFERRED.put("ISR", new int[]{0});
        PREFERRED.put("SAU", new int[]{2});
        PREFERRED.put("IRN", new int[]{2, 1});
        PREFERRED.put("IRQ", new int[]{1, 2});
        PREFERRED.put("SYR", new int[]{1, 2});
        PREFERRED.put("JOR", new int[]{1, 2});
        PREFERRED.put("LBN", new int[]{1, 2});
        PREFERRED.put("EGY", new int[]{1, 3});
        PREFERRED.put("LBY", new int[]{2, 1});
        PREFERRED.put("TUN", new int[]{1});
        PREFERRED.put("DZA", new int[]{2, 1});
        PREFERRED.put("MAR", new int[]{1, 2});
        PREFERRED.put("KAZ", new int[]{6, 0});
        PREFERRED.put("UZB", new int[]{0, 2});
        PREFERRED.put("TKM", new int[]{2});
        PREFERRED.put("KGZ", new int[]{1});
        PREFERRED.put("TJK", new int[]{1, 2});
        PREFERRED.put("GEO", new int[]{1});
        PREFERRED.put("ARM", new int[]{1, 0});
        PREFERRED.put("AZE", new int[]{0, 1});
        PREFERRED.put("CYP", new int[]{4});
        // Asia
        PREFERRED.put("CHN", new int[]{1, 3});
        PREFERRED.put("JPN", new int[]{1});
        PREFERRED.put("KOR", new int[]{0, 1});
        PREFERRED.put("PRK", new int[]{1, 0});
        PREFERRED.put("IND", new int[]{4, 2});
        PREFERRED.put("PAK", new int[]{2});
        PREFERRED.put("BGD", new int[]{2, 1});
        PREFERRED.put("MMR", new int[]{3, 2});
        PREFERRED.put("THA", new int[]{0, 1});
        PREFERRED.put("VNM", new int[]{1, 3});
        PREFERRED.put("IDN", new int[]{1});
        PREFERRED.put("MYS", new int[]{0, 1});
        PREFERRED.put("PHL", new int[]{0, 1});
        PREFERRED.put("LKA", new int[]{4, 3});
        // Americas
        PREFERRED.put("USA", new int[]{0, 1});
        PREFERRED.put("CAN", new int[]{1});
        PREFERRED.put("MEX", new int[]{2, 1});
        PREFERRED.put("BRA", new int[]{2, 3});
        PREFERRED.put("ARG", new int[]{0, 3});
        PREFERRED.put("COL", new int[]{3, 0});
        PREFERRED.put("PER", new int[]{1});
        PREFERRED.put("CHL", new int[]{1, 0});
        PREFERRED.put("VEN", new int[]{3, 0});
        PREFERRED.put("ECU", new int[]{3, 0});
        PREFERRED.put("BOL", new int[]{1, 3});
        PREFERRED.put("PRY", new int[]{1, 0});
        PREFERRED.put("URY", new int[]{0});
        PREFERRED.put("CUB", new int[]{0, 1});
        PREFERRED.put("GTM", new int[]{0});
        PREFERRED.put("HND", new int[]{0});
        PREFERRED.put("SLV", new int[]{0});
        PREFERRED.put("NIC", new int[]{0});
        PREFERRED.put("CRI", new int[]{0, 1});
        PREFERRED.put("PAN", new int[]{0, 1});
        // Africa
        PREFERRED.put("NGA", new int[]{2});
        PREFERRED.put("ZAF", new int[]{2, 3});
        PREFERRED.put("KEN", new int[]{1, 2});
        PREFERRED.put("ETH", new int[]{2, 3});
        PREFERRED.put("TZA", new int[]{2, 0});
        PREFERRED.put("COD", new int[]{0});
        PREFERRED.put("SDN", new int[]{1, 2});
        PREFERRED.put("SSD", new int[]{0, 2});
        PREFERRED.put("SOM", new int[]{0});
        PREFERRED.put("GHA", new int[]{1, 3});
        PREFERRED.put("CMR", new int[]{2, 1});
        PREFERRED.put("CIV", new int[]{4, 2});
        PREFERRED.put("SEN", new int[]{2, 3});
        PREFERRED.put("MLI", new int[]{2, 3});
        PREFERRED.put("BFA", new int[]{1, 2});
        PREFERRED.put("NER", new int[]{4, 2});
        PREFERRED.put("TCD", new int[]{0, 3});
        PREFERRED.put("CAF", new int[]{0, 3});
        PREFERRED.put("COG", new int[]{2, 3});
        PREFERRED.put("AGO", new int[]{1});
        PREFERRED.put("MOZ", new int[]{2, 1});
        PREFERRED.put("MDG", new int[]{1, 2});
        PREFERRED.put("ZMB", new int[]{2});
        PREFERRED.put("ZWE", new int[]{2, 3});
        PREFERRED.put("MWI", new int[]{1});
        PREFERRED.put("NAM", new int[]{0, 2});
        PREFERRED.put("BWA", new int[]{0});
        // Oceania
        PREFERRED.put("AUS", new int[]{0});
        PREFERRED.put("NZL", new int[]{0, 1});
    }

    record Country(String iso, String name, double[][][] rings, double[] bbox, double area) {}

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();

        // Phase 1: Load
        System.out.println("Loading countries...");
        List<Country> countries = loadCountries();
        int n = countries.size();
        System.out.println("  " + n + " countries loaded");

        // Phase 2: Adjacency (land borders + EEZ maritime borders)
        System.out.println("Building adjacency graph (land borders)...");
        List<Set<Integer>> adjacency = buildAdjacency(countries);
        int landBorders = 0;
        for (var s : adjacency) landBorders += s.size();
        landBorders /= 2;
        System.out.println("  " + landBorders + " land borders found");

        System.out.println("Adding EEZ maritime borders...");
        int seaBorders = addEezAdjacency(countries, adjacency);
        System.out.println("  " + seaBorders + " maritime borders added");
        int borders = landBorders + seaBorders;

        // Phase 3: Graph coloring
        System.out.println("Graph coloring...");
        int[] colors = graphColor(countries, adjacency);

        // Verify
        int conflicts = 0;
        for (int i = 0; i < n; i++)
            for (int j : adjacency.get(i))
                if (i < j && colors[i] == colors[j]) conflicts++;

        int gotPreferred = 0, totalPreferred = 0;
        for (int i = 0; i < n; i++) {
            int[] pref = PREFERRED.get(countries.get(i).iso);
            if (pref != null) {
                totalPreferred++;
                for (int p : pref)
                    if (colors[i] == p) { gotPreferred++; break; }
            }
        }
        System.out.println("  Conflicts: " + conflicts);
        System.out.println("  Preferred achieved: " + gotPreferred + "/" + totalPreferred);

        // Phase 4: Write output
        String outputPath = "src/main/resources/country_colors.properties";
        try (PrintWriter pw = new PrintWriter(new FileWriter(outputPath))) {
            pw.println("# Country color assignments (ISO_A3=colorIndex)");
            pw.println("# Generated by ComputeColors - do not edit manually");
            pw.printf("# Palette: %d, Countries: %d, Conflicts: %d%n",
                    PALETTE.length, n, conflicts);
            for (int i = 0; i < n; i++) {
                pw.println(countries.get(i).iso + "=" + colors[i]);
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("Done in " + elapsed + " ms → " + outputPath);
    }

    private static List<Country> loadCountries() throws Exception {
        List<Country> result = new ArrayList<>();
        try (InputStream is = ComputeColors.class.getResourceAsStream("/countries.geojson")) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);
            for (JsonNode feature : root.get("features")) {
                JsonNode props = feature.get("properties");
                String iso = props.has("ISO_A3") ? props.get("ISO_A3").asText() : "UNK";
                String name = props.has("NAME") ? props.get("NAME").asText() : "Unknown";

                JsonNode geom = feature.get("geometry");
                List<double[][]> rings = new ArrayList<>();
                extractExteriorRings(geom, rings);

                // Compute bbox
                double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
                double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
                for (double[][] ring : rings) {
                    for (double[] pt : ring) {
                        minLon = Math.min(minLon, pt[0]);
                        minLat = Math.min(minLat, pt[1]);
                        maxLon = Math.max(maxLon, pt[0]);
                        maxLat = Math.max(maxLat, pt[1]);
                    }
                }
                double[] bbox = {minLon, minLat, maxLon, maxLat};

                // Flatten all rings into one array for the record
                // Store as array of rings, each ring is double[][2]
                // Compute polygon area using Shoelace formula
                double area = 0;
                for (double[][] ring : rings) {
                    area += Math.abs(shoelaceArea(ring));
                }

                result.add(new Country(iso, name,
                        rings.toArray(new double[0][0][]), bbox, area));
            }
        }
        return result;
    }

    private static double shoelaceArea(double[][] ring) {
        double sum = 0;
        for (int i = 0; i < ring.length - 1; i++) {
            sum += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1];
        }
        return sum / 2.0;
    }

    private static void extractExteriorRings(JsonNode geom, List<double[][]> rings) {
        String type = geom.get("type").asText();
        JsonNode coords = geom.get("coordinates");
        if ("Polygon".equals(type)) {
            rings.add(parseRing(coords.get(0)));
        } else if ("MultiPolygon".equals(type)) {
            for (JsonNode poly : coords) {
                rings.add(parseRing(poly.get(0)));
            }
        }
    }

    private static double[][] parseRing(JsonNode ringNode) {
        double[][] ring = new double[ringNode.size()][2];
        for (int i = 0; i < ringNode.size(); i++) {
            ring[i][0] = ringNode.get(i).get(0).asDouble();
            ring[i][1] = ringNode.get(i).get(1).asDouble();
        }
        return ring;
    }

    /**
     * Load EEZ zones and add maritime adjacency.
     * Two countries are maritime neighbors if their EEZ zones touch.
     * Maps EEZ ISO codes to country indices via the countries list.
     */
    private static int addEezAdjacency(List<Country> countries, List<Set<Integer>> adjacency)
            throws Exception {
        // Build ISO → index map
        Map<String, Integer> isoToIdx = new HashMap<>();
        for (int i = 0; i < countries.size(); i++) {
            isoToIdx.put(countries.get(i).iso, i);
        }

        // Load EEZ zones
        List<String> eezIsos = new ArrayList<>();
        List<double[][][]> eezRings = new ArrayList<>();
        List<double[]> eezBboxes = new ArrayList<>();

        try (InputStream is = ComputeColors.class.getResourceAsStream("/eez_zones.geojson")) {
            if (is == null) return 0;
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);
            for (JsonNode feature : root.get("features")) {
                String iso = feature.get("properties").get("ISO_A3").asText();
                if (!isoToIdx.containsKey(iso)) continue;

                List<double[][]> rings = new ArrayList<>();
                extractExteriorRings(feature.get("geometry"), rings);
                if (rings.isEmpty()) continue;

                double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
                double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
                for (double[][] ring : rings) {
                    for (double[] pt : ring) {
                        minLon = Math.min(minLon, pt[0]);
                        minLat = Math.min(minLat, pt[1]);
                        maxLon = Math.max(maxLon, pt[0]);
                        maxLat = Math.max(maxLat, pt[1]);
                    }
                }

                eezIsos.add(iso);
                eezRings.add(rings.toArray(new double[0][0][]));
                eezBboxes.add(new double[]{minLon, minLat, maxLon, maxLat});
            }
        }

        // Find EEZ adjacency
        int added = 0;
        int eezN = eezIsos.size();
        for (int i = 0; i < eezN; i++) {
            int idxI = isoToIdx.get(eezIsos.get(i));
            for (int j = i + 1; j < eezN; j++) {
                int idxJ = isoToIdx.get(eezIsos.get(j));
                // Skip if already adjacent from land borders
                if (adjacency.get(idxI).contains(idxJ)) continue;
                if (!bboxOverlap(eezBboxes.get(i), eezBboxes.get(j), 0.05)) continue;

                if (eezZonesAdjacent(eezRings.get(i), eezRings.get(j))) {
                    adjacency.get(idxI).add(idxJ);
                    adjacency.get(idxJ).add(idxI);
                    added++;
                }
            }
        }
        return added;
    }

    private static boolean eezZonesAdjacent(double[][][] ringsA, double[][][] ringsB) {
        for (double[][] ringA : ringsA) {
            double[] bboxA = ringBbox(ringA);
            for (double[][] ringB : ringsB) {
                double[] bboxB = ringBbox(ringB);
                if (!bboxOverlap(bboxA, bboxB, 0.05)) continue;
                if (ringsShareBorder(ringA, ringB)) return true;
            }
        }
        return false;
    }

    private static List<Set<Integer>> buildAdjacency(List<Country> countries) {
        int n = countries.size();
        List<Set<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new HashSet<>());

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (!bboxOverlap(countries.get(i).bbox, countries.get(j).bbox, 0.02))
                    continue;
                if (countriesAdjacent(countries.get(i), countries.get(j))) {
                    adj.get(i).add(j);
                    adj.get(j).add(i);
                }
            }
        }
        return adj;
    }

    private static boolean bboxOverlap(double[] a, double[] b, double eps) {
        return !(a[2] + eps < b[0] || b[2] + eps < a[0] ||
                 a[3] + eps < b[1] || b[3] + eps < a[1]);
    }

    private static boolean countriesAdjacent(Country a, Country b) {
        for (double[][] ringA : a.rings) {
            double[] bboxA = ringBbox(ringA);
            for (double[][] ringB : b.rings) {
                double[] bboxB = ringBbox(ringB);
                if (!bboxOverlap(bboxA, bboxB, 0.02)) continue;
                if (ringsShareBorder(ringA, ringB)) return true;
            }
        }
        return false;
    }

    private static double[] ringBbox(double[][] ring) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] pt : ring) {
            minX = Math.min(minX, pt[0]);
            minY = Math.min(minY, pt[1]);
            maxX = Math.max(maxX, pt[0]);
            maxY = Math.max(maxY, pt[1]);
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    private static final double THRESHOLD = 0.02;
    private static final double THRESHOLD_SQ = THRESHOLD * THRESHOLD;

    private static boolean ringsShareBorder(double[][] r1, double[][] r2) {
        // First: fast point-to-point check (catches shared vertices)
        int pStep1 = Math.max(1, r1.length / 300);
        int pStep2 = Math.max(1, r2.length / 300);
        for (int i = 0; i < r1.length; i += pStep1) {
            for (int j = 0; j < r2.length; j += pStep2) {
                double dx = r1[i][0] - r2[j][0], dy = r1[i][1] - r2[j][1];
                if (dx * dx + dy * dy < THRESHOLD_SQ) return true;
            }
        }
        // Second: segment-to-segment check
        int step1 = Math.max(1, r1.length / 200);
        int step2 = Math.max(1, r2.length / 200);
        for (int i = 0; i < r1.length - 1; i += step1) {
            for (int j = 0; j < r2.length - 1; j += step2) {
                if (segmentsClose(r1[i], r1[i + 1], r2[j], r2[j + 1]))
                    return true;
            }
        }
        return false;
    }

    private static boolean segmentsClose(double[] p1, double[] p2, double[] p3, double[] p4) {
        return ptSegDistSq(p1, p3, p4) < THRESHOLD_SQ
            || ptSegDistSq(p2, p3, p4) < THRESHOLD_SQ
            || ptSegDistSq(p3, p1, p2) < THRESHOLD_SQ
            || ptSegDistSq(p4, p1, p2) < THRESHOLD_SQ;
    }

    private static double ptSegDistSq(double[] p, double[] a, double[] b) {
        double dx = b[0] - a[0], dy = b[1] - a[1];
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) {
            double ex = p[0] - a[0], ey = p[1] - a[1];
            return ex * ex + ey * ey;
        }
        double t = Math.max(0, Math.min(1, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / lenSq));
        double ex = p[0] - a[0] - t * dx;
        double ey = p[1] - a[1] - t * dy;
        return ex * ex + ey * ey;
    }

    /**
     * Graph coloring with greedy initialization + iterative local search.
     *
     * Phase 1: Greedy assignment (largest countries first, preferred colors).
     * Phase 2: Iterative improvement — for each country that didn't get preferred,
     *          try swapping its color with neighbors to see if total score improves.
     *
     * Score = sum of (area_weight * preference_bonus) for all countries.
     */
    private static int[] graphColor(List<Country> countries, List<Set<Integer>> adjacency) {
        int n = countries.size();
        int numColors = PALETTE.length;

        // Compute preference scores: score[i][c] = reward for country i getting color c
        double maxArea = 0;
        for (Country c : countries) maxArea = Math.max(maxArea, c.area);

        double[][] scores = new double[n][numColors];
        for (int i = 0; i < n; i++) {
            double weight = countries.get(i).area / (maxArea + 1);
            int[] pref = PREFERRED.get(countries.get(i).iso);
            if (pref != null) {
                for (int p = 0; p < pref.length; p++) {
                    scores[i][pref[p]] = 10.0 / (p + 1);
                }
            }
        }

        // Phase 1: Greedy (largest first)
        int[] colors = greedyColor(countries, adjacency, scores, numColors);
        double score = totalScore(colors, scores, n);
        System.out.printf("  Greedy score: %.2f%n", score);

        // Phase 2: Parallel multi-start simulated annealing
        int threads = Runtime.getRuntime().availableProcessors();
        System.out.printf("  Running %d SA instances in parallel...%n", threads);
        int[] greedyColors = Arrays.copyOf(colors, n);

        var futures = new ArrayList<java.util.concurrent.Future<int[]>>();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        for (int r = 0; r < threads; r++) {
            int seed = r;
            double[][] s = scores;
            futures.add(executor.submit(() ->
                    simulatedAnnealing(Arrays.copyOf(greedyColors, n), adjacency, s, numColors, n, seed)));
        }

        int[] bestColors = greedyColors;
        double bestScore = score;
        for (var future : futures) {
            try {
                int[] candidate = future.get();
                double candidateScore = totalScore(candidate, scores, n);
                if (candidateScore > bestScore) {
                    bestScore = candidateScore;
                    bestColors = candidate;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        executor.shutdown();
        colors = bestColors;
        score = bestScore;
        System.out.printf("  Best SA score: %.2f%n", score);
        return colors;
    }

    private static int[] greedyColor(List<Country> countries, List<Set<Integer>> adjacency,
                                      double[][] scores, int numColors) {
        int n = countries.size();
        int[] colors = new int[n];
        Arrays.fill(colors, -1);

        // Largest countries first
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(countries.get(b).area, countries.get(a).area));

        for (int idx : order) {
            Set<Integer> used = new HashSet<>();
            for (int neighbor : adjacency.get(idx)) {
                if (colors[neighbor] >= 0) used.add(colors[neighbor]);
            }
            List<Integer> available = new ArrayList<>();
            for (int c = 0; c < numColors; c++) {
                if (!used.contains(c)) available.add(c);
            }

            int chosen = -1;

            // Try preferred
            int[] pref = PREFERRED.get(countries.get(idx).iso);
            if (pref != null) {
                for (int p : pref) {
                    if (available.contains(p)) { chosen = p; break; }
                }
            }

            // Max color distance from neighbors
            if (chosen < 0 && !available.isEmpty()) {
                List<Color> neighborColors = new ArrayList<>();
                for (int neighbor : adjacency.get(idx)) {
                    if (colors[neighbor] >= 0) neighborColors.add(PALETTE[colors[neighbor]]);
                }
                if (!neighborColors.isEmpty()) {
                    double bestDist = -1;
                    for (int c : available) {
                        double minDist = Double.MAX_VALUE;
                        for (Color nc : neighborColors) {
                            double d = colorDist(PALETTE[c], nc);
                            if (d < minDist) minDist = d;
                        }
                        if (minDist > bestDist) { bestDist = minDist; chosen = c; }
                    }
                } else {
                    chosen = available.get(0);
                }
            }
            if (chosen < 0) chosen = 0;
            colors[idx] = chosen;
        }
        return colors;
    }

    /**
     * Simulated annealing: starts from greedy solution and makes random
     * single-country recolorings, accepting worse solutions with decreasing
     * probability to escape local optima.
     */
    private static int[] simulatedAnnealing(int[] initial, List<Set<Integer>> adjacency,
                                             double[][] scores, int numColors, int n,
                                             int seed) {
        Random rng = new Random(42 + seed * 1337);
        int[] current = Arrays.copyOf(initial, n);
        int[] best = Arrays.copyOf(initial, n);
        double currentScore = totalScore(current, scores, n);
        double bestScore = currentScore;

        // Precompute valid colors per country (neighbors' colors excluded)
        // Recomputed dynamically as colors change

        double temperature = 10.0;
        double coolingRate = 0.999995;
        int iterations = 10_000_000;
        int accepted = 0;

        for (int iter = 0; iter < iterations; iter++) {
            // Pick a random country
            int idx = rng.nextInt(n);
            int oldColor = current[idx];

            // Collect valid alternative colors (no neighbor conflict)
            int validCount = 0;
            int[] validColors = new int[numColors];
            for (int c = 0; c < numColors; c++) {
                if (c == oldColor) continue;
                boolean ok = true;
                for (int nb : adjacency.get(idx)) {
                    if (current[nb] == c) { ok = false; break; }
                }
                if (ok) validColors[validCount++] = c;
            }
            if (validCount == 0) continue;

            // Pick a random valid color
            int newColor = validColors[rng.nextInt(validCount)];

            // Compute score delta (only affected country changes)
            double delta = scores[idx][newColor] - scores[idx][oldColor];

            // Accept if better, or with probability exp(delta/temperature)
            if (delta > 0 || rng.nextDouble() < Math.exp(delta / temperature)) {
                current[idx] = newColor;
                currentScore += delta;
                accepted++;

                if (currentScore > bestScore) {
                    bestScore = currentScore;
                    System.arraycopy(current, 0, best, 0, n);
                }
            }

            temperature *= coolingRate;
        }

        System.out.printf("  SA: %d accepted moves, best=%.2f%n", accepted, bestScore);
        return best;
    }

    private static double totalScore(int[] colors, double[][] scores, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            if (colors[i] >= 0) sum += scores[i][colors[i]];
        }
        return sum;
    }

    private static double colorDist(Color a, Color b) {
        int dr = a.getRed() - b.getRed();
        int dg = a.getGreen() - b.getGreen();
        int db = a.getBlue() - b.getBlue();
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }
}
