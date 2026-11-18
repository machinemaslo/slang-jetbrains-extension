interface IValue
{
    float getValue();
}
struct Box<T>
{
    T value;
};
static Box<vector<float, 4>> box;
