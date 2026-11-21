#include "bindings.hpp"

float materialWeight(const CheckedMaterial& material);

int main()
{
    const CheckedMaterial material{0.25f, 0.75f};
    return materialWeight(material) == 1.0f ? 0 : 1;
}
