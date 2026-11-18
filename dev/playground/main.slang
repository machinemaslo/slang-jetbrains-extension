import lighting;

// Playground: Find Usages / Rename on shadeSurface (also used in preview.slang).
// Intentional spelling mistakes: mispellled, ligthing, shaddow.
// Shader vocabulary: StructuredBuffer RWStructuredBuffer std430 std140 SPIR-V.
StructuredBuffer<float4> gInputColors;
RWStructuredBuffer<float4> outputColors;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
    float unusedLocal = 42.0;
    float3 color = shadeSurface(gInputColors[id.x].xyz, 0.75);
    outputColors[id.x] = float4(color, 1.0);
}

// Use Reformat Code on this function, then Undo.
float badlyFormatted(float x){if(x>0){return x+1;}else{return -x;}}

// Check spelling inside strings, including immediately after an escape.
static const string message = "Hello\nmispellled shader";
