package com.securedoc.benchmark.score;

/**
 * One item a path reported: page, type and box ({@code [left, top, width, height]}, 0..1, top-left), or no
 * box when the path could not place it. Never a value.
 */
public record Prediction(int page, String type, double[] box, String source) {
}
