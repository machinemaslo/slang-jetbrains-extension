// Café: the declaration starts after a non-ASCII byte sequence.
struct PlaygroundMaterial
{
    float roughness;
};

ConstantBuffer<PlaygroundMaterial> material;
RWStructuredBuffer<float> outputValues;

[shader("compute")]
[numthreads(1, 1, 1)]
void reflectMaterial(uint3 id : SV_DispatchThreadID)
{
    outputValues[id.x] = material.roughness;
}
