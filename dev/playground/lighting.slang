// Shared function: navigate here from either caller, find usages, or rename.
public float3 shadeSurface(float3 color, float intensity)
{
    return color * intensity;
}

// Intentionally unused helper for unused-symbol highlighting.
float unusedHelper(float value)
{
    return value * 2.0;
}
