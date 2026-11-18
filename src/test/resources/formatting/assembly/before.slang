float f(float left,float right){return spirv_asm {
      result:$$float = OpFAdd   $left  $right;
    };}
