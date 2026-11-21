#include "bindings.hpp"

float materialWeight(const MaterialConstantsRelease& material)
{
    return material.roughness + material.metallic;
}
