#include "bindings.hpp"

float materialWeight(const MaterialConstants& material)
{
    return material.roughness + material.metallic;
}
