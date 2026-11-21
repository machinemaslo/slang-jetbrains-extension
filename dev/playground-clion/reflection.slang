// Ctrl-click PlaygroundMaterial here after building playground in CLion.
struct PlaygroundMaterial
{
    float roughness;
    float metallic;
};

ConstantBuffer<PlaygroundMaterial> material;
RWStructuredBuffer<float> outputValues;

float getRoughness(PlaygroundMaterial value)
{
    return value.roughness;
}

float getMetallic(PlaygroundMaterial value)
{
    return value.metallic;
}

[shader("compute")]
[numthreads(1, 1, 1)]
void reflectionMain(uint3 id : SV_DispatchThreadID)
{
    outputValues[id.x] = getRoughness(material) + getMetallic(material);
}
