import lighting;

// A second caller makes cross-file references and rename easy to exercise.
float3 previewColor(float3 color)
{
    return shadeSurface(color, 0.5);
}
