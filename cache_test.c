#include <stdio.h>
#include <riscv-pk/encoding.h>
#include "marchid.h"
#include <stdint.h>

#define L2_SIZE_BYTES   (512 * 1024)
#define TEST_SIZE_BYTES (2 * L2_SIZE_BYTES) // 1MB to force eviction
#define CACHE_BLOCK_SZ  64

// 1. Declare the array normally
// uint8_t big_array[TEST_SIZE_BYTES];

// // 2. Simple RNG implementation (No <stdlib.h> needed)
// static unsigned long int rng_state = 123; // Deterministic seed for debugging!
// int fast_rand() {
//     rng_state = rng_state * 1103515245 + 12345;
//     return (unsigned int)(rng_state / 65536) % 256; // Return byte (0-255)
// }

int main() {
    for (int i = 0; i < 10; ++i) printf("From C: %d\n", i);
    printf("Finished\n");
    return 0;
}