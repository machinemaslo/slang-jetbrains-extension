#include "bindings.hpp"

float materialWeight(const PreviewConstantsRelease& material)
{
    return material.roughness + material.metallic;
}
