#include "bindings.hpp"

int main()
{
    const HOST_TYPE material{0.25f};
    return material.roughness == 0.25f ? 0 : 1;
}
