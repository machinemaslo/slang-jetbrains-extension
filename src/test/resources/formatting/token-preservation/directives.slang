#define BODY(x) ((x) \
    + 1)
#define SOMETHING 1
#if SOMETHING
float f(){return BODY(1);}
#endif
