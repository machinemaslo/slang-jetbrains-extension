#include "bindings.hpp"

float materialWeight(const MaterialConstants& material);

int main()
{
    const MaterialConstants material{0.25f, 0.75f};
    return materialWeight(material) == 1.0f ? 0 : 1;
}
