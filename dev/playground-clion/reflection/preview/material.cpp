#include "bindings.hpp"

float materialWeight(const PreviewConstants& material)
{
    return material.roughness + material.metallic;
}
