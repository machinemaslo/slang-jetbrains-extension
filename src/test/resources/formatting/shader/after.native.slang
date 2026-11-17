[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID)
{
    float value = 1;
}
public float shade(float value)
{
    if (value > 0)
    {
        return value;
    }
    return 0;
}
