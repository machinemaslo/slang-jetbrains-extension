uint mask(uint value,uint bits){uint shifted=value << 2;return (shifted & bits) | (value >> 1);}
