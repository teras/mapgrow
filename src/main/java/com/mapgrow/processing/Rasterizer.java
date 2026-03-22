package com.mapgrow.processing;

import com.mapgrow.geo.CountryInfo;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiPolygon;

import java.awt.*;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.util.List;

public class Rasterizer {

    /**
     * Rasterizes countries into a flat int[] pixel array.
     * 0 = sea (transparent), non-zero = ARGB country color.
     */
    public int[] rasterize(double minLon, double minLat, double maxLon, double maxLat,
                            int width, int height, List<CountryInfo> countries) {
        BufferedImage colorImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        double mercMinY = latToMercatorY(maxLat);
        double mercMaxY = latToMercatorY(minLat);
        double scaleX = width / (maxLon - minLon);
        double scaleY = height / (mercMaxY - mercMinY);

        Graphics2D g = colorImage.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

        for (CountryInfo country : countries) {
            Shape shape = geometryToShape(country.geometry(), minLon, mercMinY, scaleX, scaleY);
            if (shape == null) continue;
            g.setColor(country.color());
            g.fill(shape);
        }
        g.dispose();

        // Extract as flat int[]
        int[] pixels = new int[width * height];
        colorImage.getRGB(0, 0, width, height, pixels, 0, width);
        return pixels;
    }

    private static double latToMercatorY(double lat) {
        double latRad = Math.toRadians(lat);
        return (1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad)) / Math.PI) / 2.0;
    }

    private Shape geometryToShape(Geometry geom, double minLon, double mercMinY,
                                   double scaleX, double scaleY) {
        GeneralPath path = new GeneralPath();

        if (geom instanceof org.locationtech.jts.geom.Polygon polygon) {
            addPolygonToPath(path, polygon, minLon, mercMinY, scaleX, scaleY);
        } else if (geom instanceof MultiPolygon mp) {
            for (int i = 0; i < mp.getNumGeometries(); i++) {
                addPolygonToPath(path, (org.locationtech.jts.geom.Polygon) mp.getGeometryN(i),
                        minLon, mercMinY, scaleX, scaleY);
            }
        } else {
            return null;
        }

        return path;
    }

    private void addPolygonToPath(GeneralPath path, org.locationtech.jts.geom.Polygon polygon,
                                   double minLon, double mercMinY, double scaleX, double scaleY) {
        addRingToPath(path, polygon.getExteriorRing(), minLon, mercMinY, scaleX, scaleY);
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            addRingToPath(path, polygon.getInteriorRingN(i), minLon, mercMinY, scaleX, scaleY);
        }
    }

    private void addRingToPath(GeneralPath path, LineString ring,
                                double minLon, double mercMinY, double scaleX, double scaleY) {
        Coordinate[] coords = ring.getCoordinates();
        if (coords.length == 0) return;

        double px = (coords[0].x - minLon) * scaleX;
        double py = (latToMercatorY(coords[0].y) - mercMinY) * scaleY;
        path.moveTo(px, py);

        for (int i = 1; i < coords.length; i++) {
            px = (coords[i].x - minLon) * scaleX;
            py = (latToMercatorY(coords[i].y) - mercMinY) * scaleY;
            path.lineTo(px, py);
        }
        path.closePath();
    }
}
