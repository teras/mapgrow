package com.mapgrow.map;

import com.mapgrow.geo.CountryStore;
import com.mapgrow.processing.Rasterizer;

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
    private static final int TILE_PIXELS = TILE_SIZE * TILE_SIZE;
    private static final int MIN_ZOOM = 2;
    private static final int MAX_ZOOM = 17;
    private static final String OSM_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final String CARTO_URL = "https://basemaps.cartocdn.com/light_nolabels/%d/%d/%d.png";

    // CartoDB water color (212, 218, 220) — blue-shifted gray
    private static final int WATER_R = 212, WATER_G = 218, WATER_B = 220;
    private static final int WATER_DIST_THRESHOLD = 625; // squared distance ~25²
    private static final int BLUE_TINT_MIN = 3; // min B-R difference to be considered water

    private static final float OVERLAY_ALPHA = 0.6f;
    private static final Color BG_MAP = new Color(170, 211, 223);
    private static final Color BG_TILE_LOADING = new Color(200, 220, 230);

    // Nearest country cache: 4x4 pixel blocks → 64x64 grid per tile
    private static final int CACHE_BLOCK = 4;
    private static final int CACHE_GRID = TILE_SIZE / CACHE_BLOCK; // 64

    private static final int[] DX_8 = {-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] DY_8 = {-1, -1, -1, 0, 0, 1, 1, 1};

    public enum ViewMode { MAP_WITH_OVERLAY, LAND_SEA, LAND_SEA_COLORED, NATURAL_EARTH }

    private record TileData(BufferedImage overlay, BufferedImage landMask, BufferedImage naturalEarth) {}

    private double centerX, centerY;
    private int zoom = 6;
    private ViewMode viewMode = ViewMode.MAP_WITH_OVERLAY;

    private final ConcurrentHashMap<String, BufferedImage> osmCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, TileData> tileDataCache = new ConcurrentHashMap<>();
    private final ExecutorService tileLoader = Executors.newFixedThreadPool(6);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Rasterizer rasterizer = new Rasterizer();

    private CountryStore countryStore;

    private Point dragStart;
    private double dragCenterX, dragCenterY;
    private boolean interactionEnabled = true;
    private Runnable zoomChangeListener;

    private volatile BufferedImage expansionOverlay;

    public MapPanel() {
        setBackground(BG_MAP);
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
                int newZoom = clamp(zoom - e.getWheelRotation(), MIN_ZOOM, MAX_ZOOM);
                if (newZoom != oldZoom) {
                    int mx = e.getX() - getWidth() / 2;
                    int my = e.getY() - getHeight() / 2;
                    double worldX = centerX + mx;
                    double worldY = centerY + my;
                    double scale = Math.pow(2, newZoom - oldZoom);
                    centerX = worldX * scale - mx;
                    centerY = worldY * scale - my;
                    zoom = newZoom;
                    if (zoomChangeListener != null) zoomChangeListener.run();
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

    public int getZoom() { return zoom; }

    public void setZoomChangeListener(Runnable listener) {
        this.zoomChangeListener = listener;
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

    public void setViewMode(ViewMode mode) {
        this.viewMode = mode;
        setBackground(mode == ViewMode.MAP_WITH_OVERLAY ? BG_MAP : Color.WHITE);
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
        boolean showOsm = viewMode == ViewMode.MAP_WITH_OVERLAY;

        for (int ty = tileYStart; ty <= tileYEnd; ty++) {
            for (int tx = tileXStart; tx <= tileXEnd; tx++) {
                int wrappedTx = ((tx % maxTile) + maxTile) % maxTile;
                if (ty < 0 || ty >= maxTile) continue;

                int screenX = (int) (tx * TILE_SIZE - originX);
                int screenY = (int) (ty * TILE_SIZE - originY);

                if (showOsm) {
                    BufferedImage osmTile = getOsmTile(zoom, wrappedTx, ty);
                    if (osmTile != null) {
                        g2.drawImage(osmTile, screenX, screenY, TILE_SIZE, TILE_SIZE, null);
                    } else {
                        g2.setColor(BG_TILE_LOADING);
                        g2.fillRect(screenX, screenY, TILE_SIZE, TILE_SIZE);
                    }
                }

                if (expansionOverlay == null) {
                    TileData td = getTileData(zoom, wrappedTx, ty);
                    if (td != null) {
                        BufferedImage img = switch (viewMode) {
                            case MAP_WITH_OVERLAY, LAND_SEA_COLORED -> td.overlay;
                            case LAND_SEA -> td.landMask;
                            case NATURAL_EARTH -> td.naturalEarth;
                        };
                        if (showOsm) {
                            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, OVERLAY_ALPHA));
                        }
                        g2.drawImage(img, screenX, screenY, TILE_SIZE, TILE_SIZE, null);
                        if (showOsm) {
                            g2.setComposite(originalComposite);
                        }
                    }
                }
            }
        }

        // Expansion overlay (during/after processing)
        BufferedImage expOverlay = this.expansionOverlay;
        if (expOverlay != null) {
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, OVERLAY_ALPHA));
            g2.drawImage(expOverlay, 0, 0, w, h, null);
            g2.setComposite(originalComposite);
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

    private TileData getTileData(int z, int x, int y) {
        if (countryStore == null) return null;

        String key = z + "/" + x + "/" + y;
        TileData cached = tileDataCache.get(key);
        if (cached != null) return cached;

        tileLoader.submit(() -> {
            if (tileDataCache.containsKey(key)) return;

            BufferedImage cartoTile = fetchTile(String.format(CARTO_URL, z, x, y));
            if (cartoTile == null) return;

            // Convert to ARGB and get backing array for fast pixel access
            BufferedImage cartoArgb = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gTemp = cartoArgb.createGraphics();
            gTemp.drawImage(cartoTile, 0, 0, null);
            gTemp.dispose();
            int[] cartoPixels = ((DataBufferInt) cartoArgb.getRaster().getDataBuffer()).getData();

            // Classify land/water: water must be close to water color AND blue-tinted
            boolean[] isLand = new boolean[TILE_PIXELS];
            for (int i = 0; i < TILE_PIXELS; i++) {
                int rgb = cartoPixels[i];
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int dr = r - WATER_R, dg = g - WATER_G, db = b - WATER_B;
                int distSq = dr * dr + dg * dg + db * db;
                isLand[i] = distSq > WATER_DIST_THRESHOLD || b <= r + BLUE_TINT_MIN;
            }

            // Rasterize countries
            double tileMinLon = tileToLon(x, z);
            double tileMaxLon = tileToLon(x + 1, z);
            double tileMinLat = tileToLat(y + 1, z);
            double tileMaxLat = tileToLat(y, z);

            var countries = countryStore.getCountriesIn(tileMinLon, tileMinLat, tileMaxLon, tileMaxLat);
            int[] countryPixels = rasterizer.rasterize(
                    tileMinLon, tileMinLat, tileMaxLon, tileMaxLat,
                    TILE_SIZE, TILE_SIZE, countries);

            // Land mask (black land on transparent)
            BufferedImage landMask = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            int[] landMaskPixels = ((DataBufferInt) landMask.getRaster().getDataBuffer()).getData();
            for (int i = 0; i < TILE_PIXELS; i++) {
                if (isLand[i]) landMaskPixels[i] = 0xFF000000;
            }

            // Natural Earth (raw country polygons, no CartoDB masking)
            BufferedImage naturalEarth = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            int[] nePixels = ((DataBufferInt) naturalEarth.getRaster().getDataBuffer()).getData();
            System.arraycopy(countryPixels, 0, nePixels, 0, TILE_PIXELS);

            // Overlay (CartoDB land + country colors + fill)
            BufferedImage overlay = new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB);
            int[] overlayData = ((DataBufferInt) overlay.getRaster().getDataBuffer()).getData();

            int uncoloredLand = 0;
            for (int i = 0; i < TILE_PIXELS; i++) {
                if (isLand[i] && countryPixels[i] != 0) {
                    overlayData[i] = countryPixels[i];
                } else if (isLand[i]) {
                    uncoloredLand++;
                }
            }

            if (uncoloredLand > 0) {
                fillUncoloredLand(overlayData, isLand, TILE_SIZE, TILE_SIZE);
                fillIsolatedLand(overlayData, isLand, tileMinLon, tileMaxLon, tileMinLat, tileMaxLat);
            }

            tileDataCache.put(key, new TileData(overlay, landMask, naturalEarth));
            SwingUtilities.invokeLater(this::repaint);
        });
        return null;
    }

    /**
     * Fills uncolored land pixels that are adjacent to colored pixels
     * via frontier expansion (flood fill through connected land).
     */
    private static void fillUncoloredLand(int[] pixels, boolean[] isLand, int w, int h) {
        int size = w * h;
        int[] curFrontier = new int[size];
        int[] nextFrontier = new int[size];
        boolean[] inFrontier = new boolean[size];
        int frontierSize = 0;

        for (int i = 0; i < size; i++) {
            if (isLand[i] && pixels[i] == 0) {
                int x = i % w, y = i / w;
                for (int d = 0; d < 8; d++) {
                    int nx = x + DX_8[d], ny = y + DY_8[d];
                    if (nx >= 0 && nx < w && ny >= 0 && ny < h) {
                        if (pixels[ny * w + nx] != 0) {
                            curFrontier[frontierSize++] = i;
                            inFrontier[i] = true;
                            break;
                        }
                    }
                }
            }
        }

        while (frontierSize > 0) {
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

            int[] tmp = curFrontier;
            curFrontier = nextFrontier;
            nextFrontier = tmp;
            frontierSize = nextSize;
        }
    }

    /**
     * Fills isolated land pixels (no adjacent colored land) using
     * getNearestCountry() for correct geographic assignment.
     * Uses a flat int[] cache indexed by 4x4 pixel blocks.
     */
    private void fillIsolatedLand(int[] overlayData, boolean[] isLand,
                                   double minLon, double maxLon, double minLat, double maxLat) {
        boolean stillUncolored = false;
        for (int i = 0; i < TILE_PIXELS; i++) {
            if (isLand[i] && overlayData[i] == 0) {
                stillUncolored = true;
                break;
            }
        }
        if (!stillUncolored) return;

        int[] colorCache = new int[CACHE_GRID * CACHE_GRID];
        boolean[] cacheValid = new boolean[CACHE_GRID * CACHE_GRID];

        for (int i = 0; i < TILE_PIXELS; i++) {
            if (!isLand[i] || overlayData[i] != 0) continue;

            int px = i % TILE_SIZE, py = i / TILE_SIZE;
            int cacheIdx = (px / CACHE_BLOCK) * CACHE_GRID + (py / CACHE_BLOCK);

            if (cacheValid[cacheIdx]) {
                overlayData[i] = colorCache[cacheIdx];
            } else {
                double lon = minLon + ((px + 0.5) / TILE_SIZE) * (maxLon - minLon);
                double lat = maxLat - ((py + 0.5) / TILE_SIZE) * (maxLat - minLat);
                var nearest = countryStore.getNearestCountry(lon, lat);
                int color = nearest != null ? nearest.color().getRGB() : 0;
                overlayData[i] = color;
                colorCache[cacheIdx] = color;
                cacheValid[cacheIdx] = true;
            }
        }
    }

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
            // Silently ignore network errors
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

                TileData td = tileDataCache.get(zoom + "/" + wrappedTx + "/" + ty);
                if (td == null) continue;

                int tileScreenX = (int) (tx * TILE_SIZE - originX);
                int tileScreenY = (int) (ty * TILE_SIZE - originY);
                int[] tilePixels = ((DataBufferInt) td.overlay.getRaster().getDataBuffer()).getData();

                for (int py = 0; py < TILE_SIZE; py++) {
                    int sy = tileScreenY + py;
                    if (sy < 0 || sy >= height) continue;
                    for (int px = 0; px < TILE_SIZE; px++) {
                        int sx = tileScreenX + px;
                        if (sx >= 0 && sx < width) {
                            int color = tilePixels[py * TILE_SIZE + px];
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

    private static double tileToLon(int x, int z) {
        return x / (double) (1 << z) * 360.0 - 180.0;
    }

    private static double tileToLat(int y, int z) {
        double n = Math.PI - 2.0 * Math.PI * y / (1 << z);
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    private static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    private void setCenter(double lat, double lon, int zoom) {
        int mapSize = TILE_SIZE * (1 << zoom);
        centerX = ((lon + 180.0) / 360.0) * mapSize;
        centerY = (1.0 - Math.log(Math.tan(Math.toRadians(lat)) + 1.0 / Math.cos(Math.toRadians(lat))) / Math.PI) / 2.0 * mapSize;
    }
}
