#include "bindings.hpp"

float materialWeight(const CheckedMaterial& material)
{
    return material.roughness + material.metallic;
}
