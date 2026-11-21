#include "bindings.hpp"

float materialWeight(const OptimizedMaterial& material)
{
    return material.roughness + material.metallic;
}
