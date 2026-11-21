struct PlaygroundMaterial
{
    float roughness;
    float metallic;
};

ConstantBuffer<PlaygroundMaterial> material;
RWStructuredBuffer<float> outputValues;

float weight(PlaygroundMaterial value)
{
    return value.roughness + value.metallic;
}

[shader("compute")]
[numthreads(1, 1, 1)]
void reflectionMain(uint3 id : SV_DispatchThreadID)
{
    outputValues[id.x] = weight(material);
}
