package com.mapgrow.map;

import com.mapgrow.geo.CountryStore;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapPanel extends JPanel {

    private static final int TILE_SIZE = 256;
    private static final int MIN_ZOOM = 2;
    private static final int MAX_ZOOM = 17;
    private static final String OSM_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final String CARTO_URL = "https://basemaps.cartocdn.com/light_nolabels/%d/%d/%d.png";

    // CartoDB water color
    private static final int WATER_R = 212, WATER_G = 218, WATER_B = 220;
    private static final int WATER_TOLERANCE = 20;

    private double centerX, centerY;
    private int zoom = 6;

    private final ConcurrentHashMap<String, BufferedImage> osmCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, BufferedImage> overlayTileCache = new ConcurrentHashMap<>();
    private final ExecutorService tileLoader = Executors.newFixedThreadPool(6);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private CountryStore countryStore;

    private Point dragStart;
    private double dragCenterX, dragCenterY;
    private boolean interactionEnabled = true;

    // Full-viewport overlay for sea expansion (set during processing)
    private volatile BufferedImage expansionOverlay;
    private float overlayAlpha = 0.6f;

    public MapPanel() {
        setBackground(new Color(170, 211, 223));
        setCenter(38.0, 24.0, zoom);

        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!interactionEnabled) return;
                dragStart = e.getPoint();
                dragCenterX = centerX;
                dragCenterY = centerY;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!interactionEnabled || dragStart == null) return;
                centerX = dragCenterX - (e.getX() - dragStart.x);
                centerY = dragCenterY - (e.getY() - dragStart.y);
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (!interactionEnabled) return;
                int oldZoom = zoom;
                int newZoom = zoom - e.getWheelRotation();
                newZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, newZoom));
                if (newZoom != oldZoom) {
                    int mx = e.getX() - getWidth() / 2;
                    int my = e.getY() - getHeight() / 2;
                    double worldX = centerX + mx;
                    double worldY = centerY + my;
                    double scale = Math.pow(2, newZoom - oldZoom);
                    centerX = worldX * scale - mx;
                    centerY = worldY * scale - my;
                    zoom = newZoom;
                    repaint();
                }
            }
        };
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        addMouseWheelListener(mouseHandler);
        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
    }

    public void setCountryStore(CountryStore store) {
        this.countryStore = store;
        repaint();
    }

    public void setInteractionEnabled(boolean enabled) {
        this.interactionEnabled = enabled;
        setCursor(Cursor.getPredefinedCursor(enabled ? Cursor.MOVE_CURSOR : Cursor.WAIT_CURSOR));
    }

    public void setExpansionOverlay(BufferedImage image) {
        this.expansionOverlay = image;
        repaint();
    }

    public void clearExpansionOverlay() {
        this.expansionOverlay = null;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;

        int w = getWidth();
        int h = getHeight();
        double originX = centerX - w / 2.0;
        double originY = centerY - h / 2.0;
        int maxTile = 1 << zoom;

        int tileXStart = (int) Math.floor(originX / TILE_SIZE);
        int tileYStart = (int) Math.floor(originY / TILE_SIZE);
        int tileXEnd = (int) Math.floor((originX + w) / TILE_SIZE);
        int tileYEnd = (int) Math.floor((originY + h) / TILE_SIZE);

        Composite originalComposite = g2.getComposite();

        for (int ty = tileYStart; ty <= tileYEnd; ty++) {
            for (int tx = tileXStart; tx <= tileXEnd; tx++) {
                int wrappedTx = ((tx % maxTile) + maxTile) % maxTile;
                if (ty < 0 || ty >= maxTile) continue;

                int screenX = (int) (tx * TILE_SIZE - originX);
                int screenY = (int) (ty * TILE_SIZE - originY);

                // Draw OSM tile
                BufferedImage osmTile = getOsmTile(zoom, wrappedTx, ty);
                if (osmTile != null) {
                    g2.drawImage(osmTile, screenX, screenY, TILE_SIZE, TILE_SIZE, null);
                } else {
                    g2.setColor(new Color(200, 220, 230));
                    g2.fillRect(screenX, screenY, TILE_SIZE, TILE_SIZE);
                }

                // Draw country overlay tile (if no expansion overlay active)
                if (expansionOverlay == null) {
                    BufferedImage overlayTile = getOverlayTile(zoom, wrappedTx, ty);
                    if (overlayTile != null) {
                        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, overlayAlpha));
                        g2.drawImage(overlayTile, screenX, screenY, TILE_SIZE, TILE_SIZE, null);
                        g2.setComposite(originalComposite);
                    }
                }
            }
        }

        // Draw expansion overlay (during/after processing)
        BufferedImage expOverlay = this.expansionOverlay;
        if (expOverlay != null) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, overlayAlpha));
            g2.drawImage(expOverlay, 0, 0, w, h, null);
            g2.setComposite(originalComposite);
        }

        // Crosshair
        if (expansionOverlay == null) {
            g2.setColor(new Color(0, 0, 0, 80));
            g2.drawLine(w / 2 - 10, h / 2, w / 2 + 10, h / 2);
            g2.drawLine(w / 2, h / 2 - 10, w / 2, h / 2 + 10);
        }
    }

    private BufferedImage getOsmTile(int z, int x, int y) {
        String key = "osm/" + z + "/" + x + "/" + y;
        BufferedImage cached = osmCache.get(key);
        if (cached != null) return cached;

        tileLoader.submit(() -> {
            if (osmCache.containsKey(key)) return;
            BufferedImage img = fetchTile(String.format(OSM_URL, z, x, y));
            if (img != null) {
                osmCache.put(key, img);
                SwingUtilities.invokeLater(this::repaint);
            }
        });
        return null;
    }

    private BufferedImage getOverlayTile(int z, int x, int y) {
        if (countryStore == null) return null;

        String key = z + "/" + x + "/" + y;
        BufferedImage cached = overlayTileCache.get(key);
        if (cached != null) return cached;

        tileLoader.submit(() -> {
            if (overlayTileCache.containsKey(key)) return;

            // Fetch CartoDB clean tile
            BufferedImage cartoTile = fetchTile(String.format(CARTO_URL, z, x, y));
            if (cartoTile == null) return;

            // Build water mask from CartoDB tile
            boolean[] isLand = new boolean[TILE_SIZE * TILE_SIZE];
            BufferedImage rgba = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gTemp = rgba.createGraphics();
            gTemp.drawImage(cartoTile, 0, 0, null);
            gTemp.dispose();

            // Compute distance from water color for each pixel,
            // then threshold at midpoint between water and land colors.
            // Water color ≈ (212,218,220), land ≈ (250,250,248).
            // Midpoint distance ≈ 25. Anything closer to water = water.
            int[] dist = new int[TILE_SIZE * TILE_SIZE];
            for (int py = 0; py < TILE_SIZE; py++) {
                for (int px = 0; px < TILE_SIZE; px++) {
                    int rgb = rgba.getRGB(px, py);
                    int r = (rgb >> 16) & 0xFF;
                    int g = (rgb >> 8) & 0xFF;
                    int b = rgb & 0xFF;
                    int dr = r - WATER_R, dg = g - WATER_G, db = b - WATER_B;
                    dist[py * TILE_SIZE + px] = dr * dr + dg * dg + db * db;
                }
            }

            // Threshold: squared distance. Water=(212,218,220), Land≈(250,250,248)
            // Midpoint squared distance ≈ 25² = 625
            int threshold = 625;
            for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                isLand[i] = dist[i] > threshold;
            }

            // Rasterize countries onto this tile
            double tileMinLon = tileToLon(x, z);
            double tileMaxLon = tileToLon(x + 1, z);
            double tileMinLat = tileToLat(y + 1, z);
            double tileMaxLat = tileToLat(y, z);

            var countries = countryStore.getCountriesIn(tileMinLon, tileMinLat, tileMaxLon, tileMaxLat);

            com.mapgrow.processing.Rasterizer rasterizer = new com.mapgrow.processing.Rasterizer();
            int[] countryPixels = rasterizer.rasterize(
                    tileMinLon, tileMinLat, tileMaxLon, tileMaxLat,
                    TILE_SIZE, TILE_SIZE, countries);

            // Combine: CartoDB determines land/sea, GeoJSON determines country color
            BufferedImage overlay = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            int[] overlayData = ((DataBufferInt) overlay.getRaster().getDataBuffer()).getData();

            int uncoloredLand = 0;
            for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                if (isLand[i] && countryPixels[i] != 0) {
                    overlayData[i] = countryPixels[i];
                } else if (isLand[i]) {
                    uncoloredLand++;
                }
            }

            // Second pass: expand country colors to fill uncolored land pixels
            if (uncoloredLand > 0) {
                fillUncoloredLand(overlayData, isLand, TILE_SIZE, TILE_SIZE);

                // Third pass: if still uncolored land (isolated islands with no country polygon),
                // find nearest country geographically
                boolean stillUncolored = false;
                for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                    if (isLand[i] && overlayData[i] == 0) {
                        stillUncolored = true;
                        break;
                    }
                }

                if (stillUncolored) {
                    // Per-pixel nearest country lookup for each uncolored land pixel
                    // Cache results: same country within a tile is likely reused
                    java.util.Map<String, Integer> countryColorCache = new java.util.HashMap<>();

                    for (int i = 0; i < TILE_SIZE * TILE_SIZE; i++) {
                        if (isLand[i] && overlayData[i] == 0) {
                            int px = i % TILE_SIZE, py = i / TILE_SIZE;
                            double lon = tileMinLon + ((px + 0.5) / TILE_SIZE) * (tileMaxLon - tileMinLon);
                            double lat = tileMaxLat - ((py + 0.5) / TILE_SIZE) * (tileMaxLat - tileMinLat);

                            // Quantize to reduce lookups (group pixels in ~4x4 blocks)
                            String cacheKey = (px / 4) + "," + (py / 4);
                            Integer cachedColor = countryColorCache.get(cacheKey);
                            if (cachedColor != null) {
                                overlayData[i] = cachedColor;
                            } else {
                                var nearest = countryStore.getNearestCountry(lon, lat);
                                if (nearest != null) {
                                    int color = nearest.color().getRGB();
                                    overlayData[i] = color;
                                    countryColorCache.put(cacheKey, color);
                                }
                            }
                        }
                    }
                }
            }

            overlayTileCache.put(key, overlay);
            SwingUtilities.invokeLater(this::repaint);
        });
        return null;
    }

    /**
     * Expand country colors to fill ALL uncolored land pixels within a tile.
     * Two phases:
     * 1) Frontier expansion from colored land to adjacent uncolored land
     * 2) For isolated land clusters (no country polygon at all), find nearest
     *    colored pixel on the tile and assign that color
     */
    private static void fillUncoloredLand(int[] pixels, boolean[] isLand, int w, int h) {
        int size = w * h;
        int[] curFrontier = new int[size];
        int[] nextFrontier = new int[size];
        boolean[] inFrontier = new boolean[size];
        int frontierSize = 0;

        // Find uncolored land adjacent to ANY colored pixel (land or already-filled)
        for (int i = 0; i < size; i++) {
            if (isLand[i] && pixels[i] == 0) {
                int x = i % w, y = i / w;
                for (int d = 0; d < 8; d++) {
                    int nx = x + DX_8[d], ny = y + DY_8[d];
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        int ni = ny * w + nx;
                        if (pixels[ni] != 0) {
                            curFrontier[frontierSize++] = i;
                            inFrontier[i] = true;
                            break;
                        }
                    }
                }
            }
        }

        // Phase 1: expand from colored neighbors
        while (frontierSize > 0) {
            // Color all frontier pixels
            for (int f = 0; f < frontierSize; f++) {
                int idx = curFrontier[f];
                int x = idx % w, y = idx / w;
                for (int d = 0; d < 8; d++) {
                    int nx = x + DX_8[d], ny = y + DY_8[d];
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        int ni = ny * w + nx;
                        if (pixels[ni] != 0) {
                            pixels[idx] = pixels[ni];
                            break;
                        }
                    }
                }
            }

            // Build next frontier (separate array to avoid overwrite bug)
            int nextSize = 0;
            java.util.Arrays.fill(inFrontier, false);
            for (int f = 0; f < frontierSize; f++) {
                int idx = curFrontier[f];
                int x = idx % w, y = idx / w;
                for (int d = 0; d < 8; d++) {
                    int nx = x + DX_8[d], ny = y + DY_8[d];
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        int ni = ny * w + nx;
                        if (isLand[ni] && pixels[ni] == 0 && !inFrontier[ni]) {
                            nextFrontier[nextSize++] = ni;
                            inFrontier[ni] = true;
                        }
                    }
                }
            }

            // Swap
            int[] tmp = curFrontier;
            curFrontier = nextFrontier;
            nextFrontier = tmp;
            frontierSize = nextSize;
        }

        // Phase 2: handle isolated land with no country polygon at all
        // Find nearest colored pixel for each remaining uncolored land pixel
        boolean hasUncolored = false;
        for (int i = 0; i < size; i++) {
            if (isLand[i] && pixels[i] == 0) {
                hasUncolored = true;
                break;
            }
        }

        if (hasUncolored) {
            // BFS from ALL colored pixels simultaneously to find nearest color for each uncolored
            java.util.Arrays.fill(inFrontier, false);
            frontierSize = 0;
            for (int i = 0; i < size; i++) {
                if (pixels[i] != 0) {
                    curFrontier[frontierSize++] = i;
                    inFrontier[i] = true;
                }
            }

            // nearestColor[i] = color of nearest colored pixel
            int[] nearestColor = new int[size];
            System.arraycopy(pixels, 0, nearestColor, 0, size);

            while (frontierSize > 0) {
                int nextSize = 0;
                for (int f = 0; f < frontierSize; f++) {
                    int idx = curFrontier[f];
                    int x = idx % w, y = idx / w;
                    int color = nearestColor[idx];
                    for (int d = 0; d < 8; d++) {
                        int nx = x + DX_8[d], ny = y + DY_8[d];
                        if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                            int ni = ny * w + nx;
                            if (!inFrontier[ni]) {
                                nearestColor[ni] = color;
                                inFrontier[ni] = true;
                                nextFrontier[nextSize++] = ni;
                            }
                        }
                    }
                }
                int[] tmp = curFrontier;
                curFrontier = nextFrontier;
                nextFrontier = tmp;
                frontierSize = nextSize;
            }

            // Apply to uncolored land
            for (int i = 0; i < size; i++) {
                if (isLand[i] && pixels[i] == 0 && nearestColor[i] != 0) {
                    pixels[i] = nearestColor[i];
                }
            }
        }
    }

    private static final int[] DX_8 = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] DY_8 = {-1, -1, -1, 0, 0, 1, 1, 1};

    private BufferedImage fetchTile(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "MapGrow/1.0 (educational project)")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() == 200) {
                return ImageIO.read(response.body());
            }
        } catch (Exception e) {
            // Silently ignore
        }
        return null;
    }

    /**
     * Builds the initial pixel array for the current viewport from overlay tiles.
     * Returns int[] where 0 = sea, non-zero = ARGB land color.
     */
    public int[] buildViewportPixels(int width, int height) {
        int[] pixels = new int[width * height];
        double originX = centerX - width / 2.0;
        double originY = centerY - height / 2.0;
        int maxTile = 1 << zoom;

        int tileXStart = (int) Math.floor(originX / TILE_SIZE);
        int tileYStart = (int) Math.floor(originY / TILE_SIZE);
        int tileXEnd = (int) Math.floor((originX + width) / TILE_SIZE);
        int tileYEnd = (int) Math.floor((originY + height) / TILE_SIZE);

        for (int ty = tileYStart; ty <= tileYEnd; ty++) {
            for (int tx = tileXStart; tx <= tileXEnd; tx++) {
                int wrappedTx = ((tx % maxTile) + maxTile) % maxTile;
                if (ty < 0 || ty >= maxTile) continue;

                String key = zoom + "/" + wrappedTx + "/" + ty;
                BufferedImage overlayTile = overlayTileCache.get(key);
                if (overlayTile == null) continue;

                int tileScreenX = (int) (tx * TILE_SIZE - originX);
                int tileScreenY = (int) (ty * TILE_SIZE - originY);

                int[] tileData = ((DataBufferInt) overlayTile.getRaster().getDataBuffer()).getData();
                for (int py = 0; py < TILE_SIZE; py++) {
                    for (int px = 0; px < TILE_SIZE; px++) {
                        int sx = tileScreenX + px;
                        int sy = tileScreenY + py;
                        if (sx >= 0 && sx < width && sy >= 0 && sy < height) {
                            int color = tileData[py * TILE_SIZE + px];
                            if (color != 0) {
                                pixels[sy * width + sx] = color;
                            }
                        }
                    }
                }
            }
        }
        return pixels;
    }

    public double[] getViewportBounds() {
        int w = getWidth();
        int h = getHeight();
        return new double[]{w, h};
    }

    private static double tileToLon(int x, int z) {
        return x / (double) (1 << z) * 360.0 - 180.0;
    }

    private static double tileToLat(int y, int z) {
        double n = Math.PI - 2.0 * Math.PI * y / (1 << z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private double worldXToLon(double worldX, int zoom) {
        int mapSize = TILE_SIZE * (1 << zoom);
        return worldX / mapSize * 360.0 - 180.0;
    }

    private double worldYToLat(double worldY, int zoom) {
        int mapSize = TILE_SIZE * (1 << zoom);
        double n = Math.PI - 2.0 * Math.PI * worldY / mapSize;
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private void setCenter(double lat, double lon, int zoom) {
        int mapSize = TILE_SIZE * (1 << zoom);
        centerX = ((lon + 180.0) / 360.0) * mapSize;
        centerY = (1.0 - Math.log(Math.tan(Math.toRadians(lat)) + 1.0 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2.0 * mapSize;
    }
}
