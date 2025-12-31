// Found in cc_banks_0_ext and mem_ext
module sram22_4096x32m8w8(
  input clk,
  input [11:0] addr,
  input [31:0] din,
  output [31:0] dout,
  input we,
  input [3:0] wmask
);
endmodule

// Found in cc_dir_ext
module sram22_1024x32m8w32(
  input clk,
  input [9:0] addr,
  input [31:0] din,
  output [31:0] dout,
  input we,
  input wmask // 1-bit mask based on usage
);
endmodule

// Found in rockettile_dcache_data_arrays_0_ext and rockettile_icache_data_arrays_0_ext
module sram22_512x64m4w8(
  input clk,
  input [8:0] addr,
  input [63:0] din,
  output [63:0] dout,
  input we,
  input [7:0] wmask
);
endmodule

// Found in rockettile_dcache_tag_array_ext and rockettile_icache_tag_array_ext
module sram22_64x24m4w24(
  input clk,
  input [5:0] addr,
  input [23:0] din,
  output [23:0] dout,
  input we,
  input wmask // 1-bit mask based on usage
);
endmodule