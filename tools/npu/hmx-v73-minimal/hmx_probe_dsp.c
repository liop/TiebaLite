#include "hmx_probe.h"

#include <HAP_compute_res.h>
#include <HAP_power.h>
#include <stdint.h>
#include <string.h>

// All HMX tiles and destinations start on 2 KiB boundaries in the one-page
// VTCM allocation. A bias register set needs 256-byte alignment.
enum { TILE_BYTES = 2048, BIAS_OFFSET = 2 * TILE_BYTES,
       OUTPUT_OFFSET = 3 * TILE_BYTES, BIAS_DUMP_OFFSET = 4 * TILE_BYTES };

int hmx_probe_open(const char *uri, remote_handle64 *handle) {
    (void)uri;
    if (!handle) return -1;
    *handle = 1;
    return 0;
}

int hmx_probe_close(remote_handle64 handle) {
    (void)handle;
    return 0;
}

int hmx_probe_run(remote_handle64 handle, uint32_t mode,
                  uint32_t *act0, uint32_t *weight0,
                  uint32_t *bias0, uint32_t *output0,
                  uint32_t *output1, uint32_t *bias_dump0,
                  uint32_t *hmx_lock_result) {
    (void)handle;
    if ((mode > 3 && (mode < 10 || mode > 19)) ||
        !act0 || !weight0 || !bias0 || !output0 || !output1 ||
        !bias_dump0 || !hmx_lock_result) return -1;
    *act0 = *weight0 = *bias0 = *output0 = *output1 = *bias_dump0 = 0;
    *hmx_lock_result = 0xffffffff;
    if (mode == 10) return 0; // FastRPC round trip only.
    if (mode == 19) {
        unsigned int capability = 0;
        int rc = HAP_compute_res_query_capability(
            HAP_COMPUTE_RES_PREEMPTION_CAPABILITY, &capability);
        *output0 = capability;
        *output1 = (uint32_t)rc;
        return 0;
    }

    unsigned int vtcm_size = 8 * 1024 * 1024;
    HAP_compute_res_query_VTCM(0, &vtcm_size, NULL, NULL, NULL);
    if (mode == 11) {
        *act0 = vtcm_size;
        return 0;
    }
    compute_res_attr_t attr;
    HAP_compute_res_attr_init(&attr);
    HAP_compute_res_attr_set_vtcm_param_v2(&attr, vtcm_size, vtcm_size, vtcm_size);
    HAP_compute_res_attr_set_hmx_param(&attr, 1);
    uint32_t context = HAP_compute_res_acquire(&attr, 1000000);
    if (!context) return -2;
    if (mode == 12) {
        HAP_compute_res_release(context);
        return 0;
    }

    void *raw = NULL;
    if (HAP_compute_res_attr_get_vtcm_ptr_v2(&attr, &raw, &vtcm_size) != 0 ||
        !raw || vtcm_size < 5 * TILE_BYTES || ((uintptr_t)raw & 2047) != 0) {
        HAP_compute_res_release(context);
        return -3;
    }

    volatile uint16_t *act = (volatile uint16_t *)raw;
    volatile uint16_t *weight = (volatile uint16_t *)((uint8_t *)raw + TILE_BYTES);
    volatile uint32_t *bias = (volatile uint32_t *)((uint8_t *)raw + BIAS_OFFSET);
    volatile uint16_t *out = (volatile uint16_t *)((uint8_t *)raw + OUTPUT_OFFSET);
    volatile uint32_t *bias_dump = (volatile uint32_t *)((uint8_t *)raw + BIAS_DUMP_OFFSET);
    for (unsigned i = 0; i < 1024; ++i) {
        act[i] = 0x3c00;             // FP16 1
        weight[i] = 0x3c00;
        out[i] = 0x7bff;             // FP16 65504 sentinel
    }
    for (unsigned i = 0; i < 32; ++i) {
        bias[i] = mode == 18 ? 0x3c000000 : 0x3c003c00;
        // Lower 32 bits per channel: scale=1 (or 0 in mode 18), output bias=1.
        bias[i + 32] = 0;          // high 32 bits of each bias entry
        bias_dump[i] = 0;
        bias_dump[i + 32] = 0;
    }
    *act0 = act[0];
    *weight0 = weight[0];
    *bias0 = bias[0];
    if (mode == 13) {
        HAP_compute_res_release(context);
        return 0;
    }

    static int power_client;
    HAP_power_request_t power;
    memset(&power, 0, sizeof(power));
    power.type = HAP_power_set_HMX;
    power.hmx.power_up = 1;
    if (HAP_power_set(&power_client, &power) != 0) {
        HAP_compute_res_release(context);
        return -5;
    }
    if (mode == 14) {
        HAP_compute_res_release(context);
        return 0;
    }

    int lock_rc = HAP_compute_res_hmx_lock(context);
    *hmx_lock_result = (uint32_t)lock_rc;
    if (lock_rc != 0) {
        HAP_compute_res_release(context);
        return -4;
    }
    if (mode == 15) {
        HAP_compute_res_hmx_unlock(context);
        HAP_compute_res_release(context);
        return 0;
    }

    asm volatile("mxclracc.hf" ::: "memory");
    if (mode == 16) {
        HAP_compute_res_hmx_unlock(context);
        HAP_compute_res_release(context);
        return 0;
    }
    asm volatile("bias = mxmem2(%0)" :: "r"(bias) : "memory");
    if (mode == 17) {
        asm volatile("mxmem2(%0) = bias" :: "r"(bias_dump) : "memory");
        *bias_dump0 = bias_dump[0];
        *output1 = bias_dump[32]; // Inspect the second 128-byte bias vector.
        HAP_compute_res_hmx_unlock(context);
        HAP_compute_res_release(context);
        return 0;
    }

    // Mode 0 isolates ACC(clear) -> CVT -> VTCM; mode 1 adds one FP16 MMA.
    // Mode 2 changes only the CVT store's spatial mask. Mode 3 uses the
    // compact :after.hf store used by GenieX after the same one-tile MMA.
    if (mode == 1 || mode == 3) {
        const uint32_t act_rt = 0x77c; // 32 input channels, 32 spatials
        const uint32_t weight_rt = TILE_BYTES - 1;
        asm volatile("{ activation.hf = mxmem(%0, %2)\n"
                     "  weight.hf = mxmem(%1, %3) }\n"
                     :: "r"(act), "r"(weight), "r"(act_rt), "r"(weight_rt)
                     : "memory");
    }

    if (mode == 3) {
        asm volatile("mxmem(%0, %1):after.hf = acc"
                     :: "r"(out), "r"(0x700) : "memory");
    } else {
        asm volatile("cvt.hf = acc(%0)" :: "r"(0) : "memory");
        asm volatile("mxmem(%0, %1) = cvt"
                     :: "r"(out), "r"(mode == 0 ? 0 : 0x700) : "memory");
    }

    asm volatile("mxmem2(%0) = bias" :: "r"(bias_dump) : "memory");
    *output0 = out[0];
    uint32_t nonzero = 0;
    for (unsigned i = 0; i < 1024; ++i) nonzero += out[i] != 0;
    *output1 = nonzero;
    *bias_dump0 = bias_dump[0];
    HAP_compute_res_hmx_unlock(context);
    HAP_compute_res_release(context);
    return 0;
}
