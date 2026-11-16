// Editor > Code Style > Slang > Naming controls these warnings.
// Alt+Enter on a name offers a verified, reference-aware rename.
struct sample_material
{
    float roughnessValue;
};

float evaluate_material(float inputValue)
{
    float localValue = inputValue * 2.0;
    return localValue;
}

float previewMaterial()
{
    return evaluate_material(0.5);
}
