#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include "marchid.h"

#define read_csr(reg) ({ unsigned long __tmp; \
  asm volatile ("csrr %0, " #reg : "=r"(__tmp)); \
  __tmp; })

#define write_csr(reg, val) do { \
  unsigned long __tmp = (unsigned long)(val); \
  asm volatile ("csrw " #reg ", %0" :: "r"(__tmp)); \
} while (0)

static void print_u64_hex(uint64_t v)
{
    uint32_t hi = (uint32_t)(v >> 32);
    uint32_t lo = (uint32_t)(v & 0xffffffffu);

    if (hi)
        printf("0x%08x%08x", hi, lo);
    else
        printf("0x%x", lo);
}

// Rocket Chip Default L1 Config is usually 16KB. 
// Reduce it to 4KB in the config to ensure the Victim Cache is used.
#define CACHE_SIZE (4 * 1024) 
#define BLOCK_SIZE 64
#define NUM_WAYS 4
#define SET_SIZE (CACHE_SIZE / NUM_WAYS) // 1KB per way

#define ARRAY_SIZE (CACHE_SIZE * 8) // Increase array size to ensure it overflows 4KB easily
volatile uint64_t buffer[ARRAY_SIZE / 8]; // uint64_t is 8 bytes

// 1. Linear Scan: Should allow prefetchers to work, fills cache cleanly.
// Baseline measurement.
void test_linear_scan() {
    printf("Starting Test 1: Linear Scan\n");
    for (int i = 0; i < ARRAY_SIZE / 8; i++) {
        buffer[i] = i;
    }
    printf("Ending Test 1: Linear Scan\n");
}

static void setup_l1d_counters(void)
{
    // Enable counters
    write_csr(mcountinhibit, 0);

    // Set counter 3 for L1D miss
    write_csr(mhpmevent3, 0x202UL);
    write_csr(mhpmcounter3, 0);

    // Set counter 4 for L1I miss
    write_csr(mhpmevent4, 0x102UL);
    write_csr(mhpmcounter4, 0);

    // Set counter 5 for L1D accesses
    write_csr(mhpmevent5, 0x0e00UL);
    write_csr(mhpmcounter5, 0);
}

int main(void)
{
    setup_l1d_counters();

    uint64_t l1d_miss_before  = read_csr(mhpmcounter3);
    uint64_t l1i_miss_before  = read_csr(mhpmcounter4);
    uint64_t l1d_accesses_before  = read_csr(mhpmcounter5);

    uint64_t start_c, end_c, start_i, end_i;
    asm volatile("csrr %0, mcycle" : "=r"(start_c));
    asm volatile("csrr %0, minstret" : "=r"(start_i));
    
    for (volatile int i = 0; i < 3; i++){
        test_linear_scan();
    }
    
    asm volatile("csrr %0, mcycle" : "=r"(end_c));
    asm volatile("csrr %0, minstret" : "=r"(end_i));

    uint64_t l1d_miss_after = read_csr(mhpmcounter3);
    uint64_t l1i_miss_after = read_csr(mhpmcounter4);
    uint64_t l1d_accesses_after = read_csr(mhpmcounter5);

    uint64_t l1d_miss = l1d_miss_after - l1d_miss_before;
    uint64_t l1i_miss = l1i_miss_after - l1i_miss_before;
    uint64_t l1d_accesses = l1d_accesses_after - l1d_accesses_before;
    uint64_t l1d_hits = l1d_accesses - l1d_miss;
    
    printf("Cycles: %lu\n", end_c - start_c);
    printf("Instructions: %lu", end_i - start_i);
    printf("\n");

    printf("L1D misses  (mhpmcounter3) = ");
    print_u64_hex(l1d_miss);
    printf("\n");

    printf("L1I misses  (mhpmcounter4) = ");
    print_u64_hex(l1i_miss);
    printf("\n");

    printf("L1D accesses  (mhpmcounter5) = ");
    print_u64_hex(l1d_accesses);
    printf("\n");

    printf("L1D hits = ");
    print_u64_hex(l1d_hits);
    printf("\n");

    return 0;
}