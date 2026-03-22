package com.mapgrow.geo;

import org.locationtech.jts.geom.Geometry;
import java.awt.Color;

public record CountryInfo(String name, String isoCode, Geometry geometry, Color color) {
}
