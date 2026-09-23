#include "hmx_probe.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

// FastRPC request 2 enables an unsigned user PD for the CDSP domain (3).
// The Android vendor libcdsprpc.so exports this API, but SDK remote.h may not
// declare the session-control extension in the cross-compile environment.
extern int remote_session_control(uint32_t request, void *data, uint32_t length);

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s MODE (0-3, 10-19)\n", argv[0]);
        return 1;
    }
    char *end = NULL;
    unsigned long requested_mode = strtoul(argv[1], &end, 0);
    if (*end || requested_mode > 19 ||
        (requested_mode > 3 && requested_mode < 10)) return 1;
    struct { int domain; int enable; } unsigned_pd = {3, 1};
    int rc = remote_session_control(2, &unsigned_pd, sizeof(unsigned_pd));
    if (rc != 0) {
        fprintf(stderr, "Enable unsigned CDSP PD failed: %#x\n", (unsigned)rc);
        return 1;
    }
    remote_handle64 handle = 0;
    const char *uri = "file:///libhmx-probe-v73.so?hmx_probe_skel_handle_invoke&_modver=1.0&_dom=cdsp";
    rc = hmx_probe_open(uri, &handle);
    if (rc != 0) {
        fprintf(stderr, "FastRPC open failed: %#x\n", (unsigned)rc);
        return 1;
    }

    {
        uint32_t mode = (uint32_t)requested_mode;
        uint32_t act = 0, weight = 0, bias = 0, out0 = 0, out1 = 0;
        uint32_t bias_dump = 0, lock = 0xffffffff;
        rc = hmx_probe_run(handle, mode, &act, &weight, &bias, &out0,
                           &out1, &bias_dump, &lock);
        printf("mode=%u rc=%#x lock=%#x act=%04x weight=%04x bias=%08x "
               "out0=%04x out1_or_nonzero=%u bias_dump=%08x\n",
               mode, (unsigned)rc, lock, act, weight, bias,
               out0, out1, bias_dump);
        fflush(stdout);
    }
    hmx_probe_close(handle);
    return rc == 0 ? 0 : 2;
}
