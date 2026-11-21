#include "bindings.hpp"

float materialWeight(const OptimizedMaterial& material);

int main()
{
    const OptimizedMaterial material{0.25f, 0.75f};
    return materialWeight(material) == 1.0f ? 0 : 1;
}
