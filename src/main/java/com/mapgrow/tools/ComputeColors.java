package com.mapgrow.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.Color;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Offline tool: computes graph coloring for country boundaries.
 * Uses color families (Blue, Red, Green...) with 3 shades each.
 * Countries get their flag's family; neighboring countries with same family
 * get different shades.
 *
 * Phase 1: Greedy family assignment (largest first, flag preferences)
 * Phase 2: Parallel simulated annealing to maximize preferred matches
 * Phase 3: Shade assignment within families
 *
 * Run with: gradle computeColors
 */
public class ComputeColors {

    // 6 color families × 3 shades = 18 total colors
    private static final String[] FAMILY_NAMES = {"Blue", "Red", "Green", "Yellow", "Orange", "Purple"};
    private static final int NUM_FAMILIES = 6;
    private static final int NUM_SHADES = 2;

    // Palette: [family][shade] → Color
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

    // Preferred color FAMILIES per country (flag-based)
    private static final Map<String, int[]> PREFERRED = new HashMap<>();
    static {
        // Europe
        PREFERRED.put("GRC", new int[]{0});          // blue
        PREFERRED.put("TUR", new int[]{1});          // red
        PREFERRED.put("RUS", new int[]{1, 0});       // red, blue
        PREFERRED.put("FRA", new int[]{0, 1});       // blue, red
        PREFERRED.put("DEU", new int[]{3, 1});       // yellow, red
        PREFERRED.put("ITA", new int[]{2, 1});       // green, red
        PREFERRED.put("ESP", new int[]{3, 1});       // yellow, red
        PREFERRED.put("PRT", new int[]{1, 2});       // red, green
        PREFERRED.put("GBR", new int[]{0, 1});       // blue, red
        PREFERRED.put("IRL", new int[]{2, 4});       // green, orange
        PREFERRED.put("NLD", new int[]{4, 0});       // orange, blue
        PREFERRED.put("BEL", new int[]{3, 1});       // yellow, red
        PREFERRED.put("CHE", new int[]{1});          // red
        PREFERRED.put("AUT", new int[]{1});          // red
        PREFERRED.put("POL", new int[]{1});          // red
        PREFERRED.put("CZE", new int[]{0, 1});       // blue, red
        PREFERRED.put("SVK", new int[]{0, 1});       // blue, red
        PREFERRED.put("HUN", new int[]{1, 2});       // red, green
        PREFERRED.put("ROU", new int[]{3, 0});       // yellow, blue
        PREFERRED.put("BGR", new int[]{2, 1});       // green, red
        PREFERRED.put("SRB", new int[]{1, 0});       // red, blue
        PREFERRED.put("HRV", new int[]{1, 0});       // red, blue
        PREFERRED.put("SVN", new int[]{0, 1});       // blue, red
        PREFERRED.put("BIH", new int[]{0, 3});       // blue, yellow
        PREFERRED.put("MNE", new int[]{1, 3});       // red, gold
        PREFERRED.put("ALB", new int[]{1});          // red
        PREFERRED.put("MKD", new int[]{1, 3});       // red, yellow
        PREFERRED.put("UKR", new int[]{0, 3});       // blue, yellow
        PREFERRED.put("BLR", new int[]{1, 2});       // red, green
        PREFERRED.put("MDA", new int[]{0, 3});       // blue, yellow
        PREFERRED.put("LTU", new int[]{3, 2});       // yellow, green
        PREFERRED.put("LVA", new int[]{1});          // red (maroon)
        PREFERRED.put("EST", new int[]{0});          // blue
        PREFERRED.put("FIN", new int[]{0});          // blue
        PREFERRED.put("SWE", new int[]{0, 3});       // blue, yellow
        PREFERRED.put("NOR", new int[]{1, 0});       // red, blue
        PREFERRED.put("DNK", new int[]{1});          // red
        PREFERRED.put("ISL", new int[]{0, 1});       // blue, red
        // Middle East / Central Asia
        PREFERRED.put("ISR", new int[]{0});          // blue
        PREFERRED.put("SAU", new int[]{2});          // green
        PREFERRED.put("IRN", new int[]{2, 1});       // green, red
        PREFERRED.put("IRQ", new int[]{1, 2});       // red, green
        PREFERRED.put("SYR", new int[]{1, 2});       // red, green
        PREFERRED.put("JOR", new int[]{1, 2});       // red, green
        PREFERRED.put("LBN", new int[]{1, 2});       // red, green
        PREFERRED.put("EGY", new int[]{1, 3});       // red, yellow
        PREFERRED.put("LBY", new int[]{2, 1});       // green, red
        PREFERRED.put("TUN", new int[]{1});          // red
        PREFERRED.put("DZA", new int[]{2, 1});       // green, red
        PREFERRED.put("MAR", new int[]{1, 2});       // red, green
        PREFERRED.put("KAZ", new int[]{0});          // blue (teal→blue)
        PREFERRED.put("UZB", new int[]{0, 2});       // blue, green
        PREFERRED.put("TKM", new int[]{2});          // green
        PREFERRED.put("KGZ", new int[]{1});          // red
        PREFERRED.put("TJK", new int[]{1, 2});       // red, green
        PREFERRED.put("GEO", new int[]{1});          // red
        PREFERRED.put("ARM", new int[]{1, 0});       // red, blue
        PREFERRED.put("AZE", new int[]{0, 1});       // blue, red
        PREFERRED.put("CYP", new int[]{4});          // orange
        // Asia
        PREFERRED.put("CHN", new int[]{1, 3});       // red, yellow
        PREFERRED.put("JPN", new int[]{1});          // red
        PREFERRED.put("KOR", new int[]{0, 1});       // blue, red
        PREFERRED.put("PRK", new int[]{1, 0});       // red, blue
        PREFERRED.put("IND", new int[]{4, 2});       // orange, green
        PREFERRED.put("PAK", new int[]{2});          // green
        PREFERRED.put("BGD", new int[]{2, 1});       // green, red
        PREFERRED.put("MMR", new int[]{3, 2});       // yellow, green
        PREFERRED.put("THA", new int[]{0, 1});       // blue, red
        PREFERRED.put("VNM", new int[]{1, 3});       // red, yellow
        PREFERRED.put("IDN", new int[]{1});          // red
        PREFERRED.put("MYS", new int[]{0, 1});       // blue, red
        PREFERRED.put("PHL", new int[]{0, 1});       // blue, red
        PREFERRED.put("LKA", new int[]{4, 3});       // orange, yellow
        // Americas
        PREFERRED.put("USA", new int[]{0, 1});       // blue, red
        PREFERRED.put("CAN", new int[]{1});          // red
        PREFERRED.put("MEX", new int[]{2, 1});       // green, red
        PREFERRED.put("BRA", new int[]{2, 3});       // green, yellow
        PREFERRED.put("ARG", new int[]{0, 3});       // blue, yellow
        PREFERRED.put("COL", new int[]{3, 0});       // yellow, blue
        PREFERRED.put("PER", new int[]{1});          // red
        PREFERRED.put("CHL", new int[]{1, 0});       // red, blue
        PREFERRED.put("VEN", new int[]{3, 0});       // yellow, blue
        PREFERRED.put("ECU", new int[]{3, 0});       // yellow, blue
        PREFERRED.put("BOL", new int[]{1, 3});       // red, yellow
        PREFERRED.put("PRY", new int[]{1, 0});       // red, blue
        PREFERRED.put("URY", new int[]{0});          // blue
        PREFERRED.put("CUB", new int[]{0, 1});       // blue, red
        PREFERRED.put("GTM", new int[]{0});          // blue
        PREFERRED.put("HND", new int[]{0});          // blue
        PREFERRED.put("SLV", new int[]{0});          // blue
        PREFERRED.put("NIC", new int[]{0});          // blue
        PREFERRED.put("CRI", new int[]{0, 1});       // blue, red
        PREFERRED.put("PAN", new int[]{0, 1});       // blue, red
        // Africa
        PREFERRED.put("NGA", new int[]{2});          // green
        PREFERRED.put("ZAF", new int[]{2, 3});       // green, yellow
        PREFERRED.put("KEN", new int[]{1, 2});       // red, green
        PREFERRED.put("ETH", new int[]{2, 3});       // green, yellow
        PREFERRED.put("TZA", new int[]{2, 0});       // green, blue
        PREFERRED.put("COD", new int[]{0});          // blue
        PREFERRED.put("SDN", new int[]{1, 2});       // red, green
        PREFERRED.put("SSD", new int[]{0, 2});       // blue, green
        PREFERRED.put("SOM", new int[]{0});          // blue
        PREFERRED.put("GHA", new int[]{1, 3});       // red, yellow
        PREFERRED.put("CMR", new int[]{2, 1});       // green, red
        PREFERRED.put("CIV", new int[]{4, 2});       // orange, green
        PREFERRED.put("SEN", new int[]{2, 3});       // green, yellow
        PREFERRED.put("MLI", new int[]{2, 3});       // green, yellow
        PREFERRED.put("BFA", new int[]{1, 2});       // red, green
        PREFERRED.put("NER", new int[]{4, 2});       // orange, green
        PREFERRED.put("TCD", new int[]{0, 3});       // blue, yellow
        PREFERRED.put("CAF", new int[]{0, 3});       // blue, yellow
        PREFERRED.put("COG", new int[]{2, 3});       // green, yellow
        PREFERRED.put("AGO", new int[]{1});          // red
        PREFERRED.put("MOZ", new int[]{2, 1});       // green, red
        PREFERRED.put("MDG", new int[]{1, 2});       // red, green
        PREFERRED.put("ZMB", new int[]{2});          // green
        PREFERRED.put("ZWE", new int[]{2, 3});       // green, yellow
        PREFERRED.put("MWI", new int[]{1});          // red
        PREFERRED.put("NAM", new int[]{0, 2});       // blue, green
        PREFERRED.put("BWA", new int[]{0});          // blue
        // Oceania
        PREFERRED.put("AUS", new int[]{0});          // blue
        PREFERRED.put("NZL", new int[]{0, 1});       // blue, red
    }

    record Country(String iso, String name, double[][][] rings, double[] bbox, double area) {}

    public static void main(String[] args) throws Exception {
        long t0 = System.currentTimeMillis();

        System.out.println("Loading countries...");
        List<Country> countries = loadCountries();
        int n = countries.size();
        System.out.println("  " + n + " countries loaded");

        System.out.println("Building adjacency graph (land borders)...");
        List<Set<Integer>> adjacency = buildAdjacency(countries);
        int landBorders = countEdges(adjacency);
        System.out.println("  " + landBorders + " land borders found");

        System.out.println("Adding EEZ maritime borders...");
        int seaBorders = addEezAdjacency(countries, adjacency);
        System.out.println("  " + seaBorders + " maritime borders added");

        // Phase 1+2: Assign families via greedy + SA
        System.out.println("Assigning color families...");
        int[] families = assignFamilies(countries, adjacency);

        // Phase 3: Assign shades within families
        System.out.println("Assigning shades...");
        int[] shades = assignShades(families, adjacency, n);

        // Verify
        int conflicts = 0;
        for (int i = 0; i < n; i++)
            for (int j : adjacency.get(i))
                if (i < j && families[i] == families[j] && shades[i] == shades[j])
                    conflicts++;

        int gotPreferred = 0, totalPreferred = 0;
        for (int i = 0; i < n; i++) {
            int[] pref = PREFERRED.get(countries.get(i).iso);
            if (pref != null) {
                totalPreferred++;
                for (int p : pref)
                    if (families[i] == p) { gotPreferred++; break; }
            }
        }
        System.out.println("  Conflicts: " + conflicts);
        System.out.println("  Preferred family achieved: " + gotPreferred + "/" + totalPreferred);

        // Write output: ISO=family.shade
        String outputPath = "src/main/resources/country_colors.properties";
        try (PrintWriter pw = new PrintWriter(new FileWriter(outputPath))) {
            pw.println("# Country color assignments (ISO_A3=family.shade)");
            pw.println("# Families: Blue=0, Red=1, Green=2, Yellow=3, Orange=4, Purple=5");
            pw.println("# Shades: 0=light, 1=medium, 2=dark");
            for (int i = 0; i < n; i++) {
                pw.println(countries.get(i).iso + "=" + families[i] + "." + shades[i]);
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("Done in " + elapsed + " ms → " + outputPath);
    }

    // ─── Family assignment (greedy + SA) ─────────────────────────────

    private static int[] assignFamilies(List<Country> countries, List<Set<Integer>> adjacency) {
        int n = countries.size();
        double[][] scores = new double[n][NUM_FAMILIES];
        for (int i = 0; i < n; i++) {
            int[] pref = PREFERRED.get(countries.get(i).iso);
            if (pref != null) {
                for (int p = 0; p < pref.length; p++) {
                    double areaBoost = 1.0 + Math.log1p(countries.get(i).area);
                    scores[i][pref[p]] = areaBoost * (10.0 / (p + 1));
                }
            }
        }

        // Greedy: largest first
        int[] families = new int[n];
        Arrays.fill(families, -1);
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(countries.get(b).area, countries.get(a).area));

        for (int idx : order) {
            Set<Integer> usedFamilies = new HashSet<>();
            for (int nb : adjacency.get(idx)) {
                if (families[nb] >= 0) {
                    // Count how many neighbors have this family
                    int f = families[nb];
                    // A family is "full" if NUM_SHADES neighbors already use it
                    int count = 0;
                    for (int nb2 : adjacency.get(idx)) {
                        if (families[nb2] == f) count++;
                    }
                    if (count >= NUM_SHADES) usedFamilies.add(f);
                }
            }

            int chosen = -1;
            int[] pref = PREFERRED.get(countries.get(idx).iso);
            if (pref != null) {
                for (int p : pref) {
                    if (!usedFamilies.contains(p)) { chosen = p; break; }
                }
            }
            if (chosen < 0) {
                for (int f = 0; f < NUM_FAMILIES; f++) {
                    if (!usedFamilies.contains(f)) { chosen = f; break; }
                }
            }
            if (chosen < 0) chosen = 0;
            families[idx] = chosen;
        }

        double greedyScore = totalScore(families, scores, n);
        System.out.printf("  Greedy family score: %.2f%n", greedyScore);

        // SA optimization
        int threads = Runtime.getRuntime().availableProcessors();
        System.out.printf("  Running %d SA instances in parallel...%n", threads);

        var futures = new ArrayList<Future<int[]>>();
        var executor = Executors.newFixedThreadPool(threads);
        for (int r = 0; r < threads; r++) {
            int seed = r;
            futures.add(executor.submit(() ->
                    saFamilies(Arrays.copyOf(families, n), adjacency, scores, n, seed)));
        }

        int[] best = families;
        double bestScore = greedyScore;
        for (var future : futures) {
            try {
                int[] candidate = future.get();
                double s = totalScore(candidate, scores, n);
                if (s > bestScore) { bestScore = s; best = candidate; }
            } catch (Exception e) { e.printStackTrace(); }
        }
        executor.shutdown();

        System.out.printf("  Best SA family score: %.2f%n", bestScore);
        return best;
    }

    private static int[] saFamilies(int[] initial, List<Set<Integer>> adjacency,
                                     double[][] scores, int n, int seed) {
        Random rng = new Random(42 + seed * 1337);
        int[] current = Arrays.copyOf(initial, n);
        int[] best = Arrays.copyOf(initial, n);
        double currentScore = totalScore(current, scores, n);
        double bestScore = currentScore;

        double temperature = 10.0;
        double coolingRate = 0.999995;

        for (int iter = 0; iter < 10_000_000; iter++) {
            int idx = rng.nextInt(n);
            int oldFamily = current[idx];
            int newFamily = rng.nextInt(NUM_FAMILIES);
            if (newFamily == oldFamily) continue;

            // Check: would this family be valid? (max NUM_SHADES same-family neighbors)
            int sameCount = 0;
            for (int nb : adjacency.get(idx)) {
                if (current[nb] == newFamily) sameCount++;
            }
            if (sameCount >= NUM_SHADES) continue;

            double delta = scores[idx][newFamily] - scores[idx][oldFamily];
            if (delta > 0 || rng.nextDouble() < Math.exp(delta / temperature)) {
                current[idx] = newFamily;
                currentScore += delta;
                if (currentScore > bestScore) {
                    bestScore = currentScore;
                    System.arraycopy(current, 0, best, 0, n);
                }
            }
            temperature *= coolingRate;
        }

        System.out.printf("  SA[%d]: best=%.2f%n", seed, bestScore);
        return best;
    }

    // ─── Shade assignment ────────────────────────────────────────────

    private static int[] assignShades(int[] families, List<Set<Integer>> adjacency, int n) {
        int[] shades = new int[n];
        Arrays.fill(shades, -1);

        // Process by family — within each family, greedy shade assignment
        for (int f = 0; f < NUM_FAMILIES; f++) {
            // Collect countries in this family, sorted by neighbor count (most constrained first)
            List<Integer> members = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (families[i] == f) members.add(i);
            }
            int family = f;
            members.sort((a, b) -> {
                int countA = 0, countB = 0;
                for (int nb : adjacency.get(a)) if (families[nb] == family) countA++;
                for (int nb : adjacency.get(b)) if (families[nb] == family) countB++;
                return countB - countA;
            });

            for (int idx : members) {
                Set<Integer> usedShades = new HashSet<>();
                for (int nb : adjacency.get(idx)) {
                    if (families[nb] == f && shades[nb] >= 0) {
                        usedShades.add(shades[nb]);
                    }
                }
                // Prefer shade 0 (normal), fallback to shade 1 (dark)
                int[] shadeOrder = {0, 1};
                int chosen = 0;
                for (int s : shadeOrder) {
                    if (!usedShades.contains(s)) { chosen = s; break; }
                }
                shades[idx] = chosen;
            }
        }
        return shades;
    }

    // ─── Scoring ─────────────────────────────────────────────────────

    private static double totalScore(int[] families, double[][] scores, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            if (families[i] >= 0) sum += scores[i][families[i]];
        }
        return sum;
    }

    // ─── Data loading ────────────────────────────────────────────────

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

                double minLon = Double.MAX_VALUE, minLat = Double.MAX_VALUE;
                double maxLon = -Double.MAX_VALUE, maxLat = -Double.MAX_VALUE;
                for (double[][] ring : rings) {
                    for (double[] pt : ring) {
                        minLon = Math.min(minLon, pt[0]); minLat = Math.min(minLat, pt[1]);
                        maxLon = Math.max(maxLon, pt[0]); maxLat = Math.max(maxLat, pt[1]);
                    }
                }
                double area = 0;
                for (double[][] ring : rings) area += Math.abs(shoelaceArea(ring));

                result.add(new Country(iso, name, rings.toArray(new double[0][0][]),
                        new double[]{minLon, minLat, maxLon, maxLat}, area));
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
            for (JsonNode poly : coords) rings.add(parseRing(poly.get(0)));
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

    // ─── Adjacency ───────────────────────────────────────────────────

    private static List<Set<Integer>> buildAdjacency(List<Country> countries) {
        int n = countries.size();
        List<Set<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) adj.add(new HashSet<>());

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (!bboxOverlap(countries.get(i).bbox, countries.get(j).bbox, 0.02)) continue;
                if (countriesAdjacent(countries.get(i), countries.get(j))) {
                    adj.get(i).add(j); adj.get(j).add(i);
                }
            }
        }
        return adj;
    }

    private static int addEezAdjacency(List<Country> countries, List<Set<Integer>> adjacency)
            throws Exception {
        Map<String, Integer> isoToIdx = new HashMap<>();
        for (int i = 0; i < countries.size(); i++) isoToIdx.put(countries.get(i).iso, i);

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
                for (double[][] ring : rings) for (double[] pt : ring) {
                    minLon = Math.min(minLon, pt[0]); minLat = Math.min(minLat, pt[1]);
                    maxLon = Math.max(maxLon, pt[0]); maxLat = Math.max(maxLat, pt[1]);
                }
                eezIsos.add(iso);
                eezRings.add(rings.toArray(new double[0][0][]));
                eezBboxes.add(new double[]{minLon, minLat, maxLon, maxLat});
            }
        }

        int added = 0;
        for (int i = 0; i < eezIsos.size(); i++) {
            int idxI = isoToIdx.get(eezIsos.get(i));
            for (int j = i + 1; j < eezIsos.size(); j++) {
                int idxJ = isoToIdx.get(eezIsos.get(j));
                if (adjacency.get(idxI).contains(idxJ)) continue;
                if (!bboxOverlap(eezBboxes.get(i), eezBboxes.get(j), 0.05)) continue;
                if (eezZonesAdjacent(eezRings.get(i), eezRings.get(j))) {
                    adjacency.get(idxI).add(idxJ); adjacency.get(idxJ).add(idxI);
                    added++;
                }
            }
        }
        return added;
    }

    private static boolean eezZonesAdjacent(double[][][] a, double[][][] b) {
        for (double[][] rA : a) {
            double[] bboxA = ringBbox(rA);
            for (double[][] rB : b) {
                if (!bboxOverlap(bboxA, ringBbox(rB), 0.05)) continue;
                if (ringsShareBorder(rA, rB)) return true;
            }
        }
        return false;
    }

    // ─── Geometry helpers ────────────────────────────────────────────

    private static int countEdges(List<Set<Integer>> adj) {
        int c = 0; for (var s : adj) c += s.size(); return c / 2;
    }

    private static boolean bboxOverlap(double[] a, double[] b, double eps) {
        return !(a[2] + eps < b[0] || b[2] + eps < a[0] ||
                 a[3] + eps < b[1] || b[3] + eps < a[1]);
    }

    private static boolean countriesAdjacent(Country a, Country b) {
        for (double[][] rA : a.rings) {
            double[] bboxA = ringBbox(rA);
            for (double[][] rB : b.rings) {
                if (!bboxOverlap(bboxA, ringBbox(rB), 0.02)) continue;
                if (ringsShareBorder(rA, rB)) return true;
            }
        }
        return false;
    }

    private static double[] ringBbox(double[][] ring) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] pt : ring) {
            minX = Math.min(minX, pt[0]); minY = Math.min(minY, pt[1]);
            maxX = Math.max(maxX, pt[0]); maxY = Math.max(maxY, pt[1]);
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    private static final double THRESHOLD = 0.02;
    private static final double THRESHOLD_SQ = THRESHOLD * THRESHOLD;

    private static boolean ringsShareBorder(double[][] r1, double[][] r2) {
        int pStep1 = Math.max(1, r1.length / 300);
        int pStep2 = Math.max(1, r2.length / 300);
        for (int i = 0; i < r1.length; i += pStep1) {
            for (int j = 0; j < r2.length; j += pStep2) {
                double dx = r1[i][0] - r2[j][0], dy = r1[i][1] - r2[j][1];
                if (dx * dx + dy * dy < THRESHOLD_SQ) return true;
            }
        }
        int step1 = Math.max(1, r1.length / 200);
        int step2 = Math.max(1, r2.length / 200);
        for (int i = 0; i < r1.length - 1; i += step1) {
            for (int j = 0; j < r2.length - 1; j += step2) {
                if (segmentsClose(r1[i], r1[i + 1], r2[j], r2[j + 1])) return true;
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
        if (lenSq == 0) { double ex = p[0]-a[0], ey = p[1]-a[1]; return ex*ex+ey*ey; }
        double t = Math.max(0, Math.min(1, ((p[0]-a[0])*dx + (p[1]-a[1])*dy) / lenSq));
        double ex = p[0]-a[0]-t*dx, ey = p[1]-a[1]-t*dy;
        return ex*ex + ey*ey;
    }
}
